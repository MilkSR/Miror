package com.selenite.scanner.share

/**
 * A catalog-independent, compact collection codec.
 *
 * Card identities are carried exactly. The grouped profile compresses the ordinary
 * `setId-prefixNumber` shape, preserving decimal padding. A generic exception lane
 * front-codes only IDs whose set portion cannot use that grammar, so one future unusual
 * ID does not expand the rest of the collection. Neither lane consults card data.
 *
 * The outer Sigil/Safe code owns corruption detection. This envelope deliberately has no
 * second CRC.
 */
object AdaptiveIdentityCodec {
    data class Extension(
        val id: Int,
        val payload: ByteArray
    ) {
        init {
            require(id > 0) { "Extension id must be positive" }
        }
    }

    fun looksLike(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == MAGIC_M && bytes[1] == MAGIC_S

    fun encode(
        manifest: MirorShareManifest,
        extensions: List<Extension> = emptyList()
    ): ByteArray {
        require(manifest.metadata.schemaVersion >= 0) { "schemaVersion must be non-negative" }
        require(manifest.metadata.catalogAssetVersion >= 0) {
            "catalogAssetVersion must be non-negative"
        }
        require(manifest.entries.size <= MAX_SOURCE_ROWS) { "Too many share rows" }

        val normalized = normalizeRows(manifest.entries)
        val identities = normalized.map { it.cardId }.distinct()
        val groupedIds = identities.filter(::isGroupableCardId)
        val genericIds = identities.filterNot(::isGroupableCardId)
        val grouped = buildGroupedPlan(groupedIds)
        val core = encodeCore(normalized, grouped, genericIds)
        require(core.size in 1..MAX_CORE_BYTES) { "Adaptive identity core is too large" }

        val orderedExtensions = extensions.sortedBy { it.id }
        require(orderedExtensions.size <= MAX_EXTENSIONS) { "Too many share extensions" }
        require(orderedExtensions.zipWithNext().none { (left, right) -> left.id == right.id }) {
            "Extension ids must be unique"
        }
        require(orderedExtensions.sumOf { it.payload.size.toLong() } <= MAX_TOTAL_EXTENSION_BYTES) {
            "Share extensions are too large"
        }

        val writer = EnvelopeWriter()
        writer.writeByte(MAGIC_M.toInt() and 0xFF)
        writer.writeByte(MAGIC_S.toInt() and 0xFF)
        writer.writeByte(ENVELOPE_VERSION)
        writer.writeByte(SHARE_TYPE_COLLECTION)
        writer.writeUleb(manifest.metadata.schemaVersion)
        writer.writeUleb(manifest.metadata.catalogAssetVersion)
        writer.writeUleb(core.size)
        writer.writeBytes(core)
        writer.writeUleb(orderedExtensions.size)
        orderedExtensions.forEach { extension ->
            require(extension.payload.size <= MAX_EXTENSION_BYTES) {
                "Extension ${extension.id} is too large"
            }
            writer.writeUleb(extension.id)
            writer.writeUleb(extension.payload.size)
            writer.writeBytes(extension.payload)
        }
        return writer.toByteArray().also {
            require(it.size <= MAX_ENVELOPE_BYTES) { "Adaptive identity envelope is too large" }
        }
    }

    fun decode(bytes: ByteArray): MirorShareDecodeResult {
        return try {
            require(bytes.size <= MAX_ENVELOPE_BYTES) {
                "Adaptive identity envelope is too large"
            }
            if (!looksLike(bytes)) {
                return MirorShareDecodeResult.InvalidData("Not an adaptive identity payload")
            }
            val cursor = EnvelopeReader(bytes)
            check(cursor.readByte() == (MAGIC_M.toInt() and 0xFF))
            check(cursor.readByte() == (MAGIC_S.toInt() and 0xFF))
            val version = cursor.readByte()
            if (version != ENVELOPE_VERSION) {
                return MirorShareDecodeResult.UnsupportedFormat(
                    "adaptive identity version $version"
                )
            }
            val shareType = cursor.readByte()
            if (shareType != SHARE_TYPE_COLLECTION) {
                return MirorShareDecodeResult.UnsupportedFormat(
                    "adaptive share type $shareType"
                )
            }

            val schemaVersion = cursor.readUleb("schema version")
            val catalogAssetVersion = cursor.readUleb("catalog asset version")
            val coreSize = cursor.readUleb("core length")
            require(coreSize in 1..MAX_CORE_BYTES) { "Adaptive identity core length is invalid" }
            val core = cursor.readBytes(coreSize)
            val entries = decodeCore(core)

            val extensionCount = cursor.readUleb("extension count")
            require(extensionCount <= MAX_EXTENSIONS) { "Too many share extensions" }
            var previousExtensionId = 0
            var totalExtensionBytes = 0L
            repeat(extensionCount) {
                val id = cursor.readUleb("extension id")
                require(id > previousExtensionId) {
                    "Extension ids must be positive, unique, and ascending"
                }
                previousExtensionId = id
                val size = cursor.readUleb("extension length")
                require(size <= MAX_EXTENSION_BYTES) { "Share extension is too large" }
                totalExtensionBytes += size
                require(totalExtensionBytes <= MAX_TOTAL_EXTENSION_BYTES) {
                    "Share extensions are too large"
                }
                cursor.skip(size)
            }
            require(cursor.remaining == 0) { "Trailing bytes after adaptive identity payload" }

            MirorShareDecodeResult.Success(
                MirorShareManifest(
                    metadata = MirorShareMetadata(schemaVersion, catalogAssetVersion),
                    encodingMode = MirorShareEncodingMode.IDENTITY_ARITH_V1,
                    entries = entries.sortedWith(ENTRY_COMPARATOR)
                )
            )
        } catch (error: IllegalArgumentException) {
            MirorShareDecodeResult.InvalidData(error.message ?: "Invalid adaptive identity payload")
        } catch (error: IllegalStateException) {
            MirorShareDecodeResult.InvalidData(error.message ?: "Invalid adaptive identity payload")
        } catch (error: ArithmeticException) {
            MirorShareDecodeResult.InvalidData(error.message ?: "Invalid adaptive identity payload")
        } catch (error: IndexOutOfBoundsException) {
            MirorShareDecodeResult.InvalidData("Invalid adaptive identity payload")
        }
    }

    private fun encodeCore(
        rows: List<MirorShareEntry>,
        groupedPlan: GroupedPlan,
        genericIds: List<String>
    ): ByteArray {
        val byId = rows.groupBy { it.cardId }.mapValues { (_, variants) ->
            variants.sortedWith(PHYSICAL_ROW_COMPARATOR)
        }
        val encoder = ArithmeticEncoder()
        val hasGenericLane = genericIds.isNotEmpty()
        encoder.uniformBit(if (hasGenericLane) 1 else 0)
        encoder.gamma(byId.size + 1)
        if (hasGenericLane) encoder.gamma(genericIds.size)
        val groupedCount = byId.size - genericIds.size
        val orderedCardIds = buildList(byId.size) {
            addAll(encodeGroupedIdentities(encoder, groupedPlan, groupedCount))
            if (hasGenericLane) addAll(encodeGenericIdentities(encoder, genericIds))
        }
        require(orderedCardIds.size == byId.size)
        require(orderedCardIds.toSet() == byId.keys)

        val orderedRows = mutableListOf<MirorShareEntry>()
        val rowCounts = mutableListOf<Int>()
        orderedCardIds.forEach { cardId ->
            val variants = byId.getValue(cardId)
            rowCounts += variants.size
            orderedRows += variants
        }
        encodePhysicalRows(encoder, rowCounts, orderedRows)
        return encoder.finish()
    }

    private fun decodeCore(core: ByteArray): List<MirorShareEntry> {
        val decoder = ArithmeticDecoder(core)
        val hasGenericLane = decoder.uniformBit() == 1
        val identityCount = decoder.gamma() - 1
        require(identityCount <= MAX_IDENTITIES) { "Too many transmitted identities" }
        val genericCount = if (hasGenericLane) {
            decoder.gamma().also {
                require(it in 1..identityCount) { "Generic identity count is invalid" }
            }
        } else {
            0
        }
        val groupedCount = identityCount - genericCount
        val budget = DecodeBudget()
        val orderedCardIds = buildList(identityCount) {
            addAll(decodeGroupedIdentities(decoder, groupedCount, budget))
            if (hasGenericLane) addAll(decodeGenericIdentities(decoder, genericCount, budget))
        }
        require(orderedCardIds.toSet().size == orderedCardIds.size) {
            "Duplicate identity across grouped and generic lanes"
        }
        val decodedRows = decodePhysicalRows(decoder, orderedCardIds)
        val normalizedRows = normalizeRows(decodedRows)
        require(normalizedRows.size == decodedRows.size) {
            "Physical entries are not canonical"
        }
        return normalizedRows
    }

    // ---------------------------------------------------------------- identities: grouped

    private fun encodeGroupedIdentities(
        encoder: ArithmeticEncoder,
        plan: GroupedPlan,
        identityCount: Int
    ): List<String> {
        encoder.gamma(plan.sets.size + 1)
        val setLcpModel = AdaptiveModel(16)
        val setRemainderCodec = SetRemainderCodec()
        var previousSet = ""
        plan.sets.forEach { set ->
            val shared = commonPrefixLength(previousSet, set.setId)
            encodeLcp(encoder, setLcpModel, shared)
            setRemainderCodec.encode(encoder, set.setId.substring(shared))
            previousSet = set.setId
        }

        if (plan.sets.isNotEmpty()) {
            encodePositiveComposition(encoder, plan.sets.map { it.identityCount })
        } else {
            require(identityCount == 0)
        }

        encoder.gamma(plan.prefixes.size + 1)
        val prefixLcpModel = AdaptiveModel(16)
        val prefixRemainderCodec = PrefixRemainderCodec()
        var previousPrefix = ""
        plan.prefixes.forEach { prefix ->
            val shared = commonPrefixLength(previousPrefix, prefix)
            encodeLcp(encoder, prefixLcpModel, shared)
            prefixRemainderCodec.encode(encoder, prefix.substring(shared))
            previousPrefix = prefix
        }

        val prefixIndex = plan.prefixes.withIndex().associate { it.value to it.index }
        val literalPresenceModel = AdaptiveModel(2)
        val runCountModel = AdaptiveModel(8)
        val runPrefixModel = AdaptiveModel(maxOf(1, plan.prefixes.size))
        plan.sets.forEach { set ->
            literalPresenceModel.encode(encoder, if (set.literals.isEmpty()) 0 else 1)
            if (set.literals.isNotEmpty()) encoder.gamma(set.literals.size)

            val runCount = set.runs.size
            if (runCount < RUN_COUNT_ESCAPE) {
                runCountModel.encode(encoder, runCount)
            } else {
                runCountModel.encode(encoder, RUN_COUNT_ESCAPE)
                encoder.gamma(runCount - (RUN_COUNT_ESCAPE - 1))
            }
            set.runs.forEach { run ->
                runPrefixModel.encode(encoder, prefixIndex.getValue(run.prefix))
            }
            if (runCount > 1) {
                encodePositiveComposition(encoder, set.runs.map { it.values.size })
            }
        }

        val paddingModel = AdaptiveModel(2)
        val orderedCardIds = mutableListOf<String>()
        plan.sets.forEach { set ->
            set.runs.forEach { run ->
                var previousNumber = 0
                run.values.forEachIndexed { index, value ->
                    if (index == 0) {
                        encoder.rice(value.number + 1, FIRST_NUMBER_RICE_PARAMETER)
                    } else {
                        encoder.rice(value.number - previousNumber, GAP_RICE_PARAMETER)
                    }
                    previousNumber = value.number
                    val canonicalWidth = value.number.toString().length
                    val padding = value.digits.length - canonicalWidth
                    require(padding >= 0)
                    paddingModel.encode(encoder, if (padding == 0) 0 else 1)
                    if (padding > 0) encoder.gamma(padding)
                    orderedCardIds += "${set.setId}-${run.prefix}${value.digits}"
                }
            }
            set.literals.forEach { suffix ->
                encoder.utf8(suffix)
                orderedCardIds += "${set.setId}-$suffix"
            }
        }
        return orderedCardIds
    }

    private fun decodeGroupedIdentities(
        decoder: ArithmeticDecoder,
        identityCount: Int,
        budget: DecodeBudget
    ): List<String> {
        val setCount = decoder.gamma() - 1
        require(setCount <= MAX_SETS) { "Too many transmitted sets" }
        val setLcpModel = AdaptiveModel(16)
        val setRemainderCodec = SetRemainderCodec()
        val setIds = ArrayList<String>(setCount)
        var previousSet = ""
        repeat(setCount) {
            val shared = decodeLcp(decoder, setLcpModel)
            require(shared <= previousSet.length) { "Invalid set-id LCP" }
            val setId = previousSet.substring(0, shared) +
                setRemainderCodec.decode(decoder, MAX_SET_ID_CHARS)
            require(setId.isNotEmpty() && setId.length <= MAX_SET_ID_CHARS) {
                "Invalid set id"
            }
            require(setId.all(::isSetIdCharacter)) { "Invalid set-id character" }
            require(previousSet.isEmpty() || compareUtf8(previousSet, setId) < 0) {
                "Set ids are not strictly ascending"
            }
            budget.spend(setId.length)
            setIds += setId
            previousSet = setId
        }

        if (setCount == 0) {
            require(identityCount == 0) { "Identities have no set dictionary" }
        } else {
            require(identityCount >= setCount) { "Set dictionary exceeds identity count" }
        }
        val setCounts = if (setCount == 0) {
            emptyList()
        } else {
            decodePositiveComposition(decoder, identityCount, setCount)
        }

        val prefixCount = decoder.gamma() - 1
        require(prefixCount <= MAX_PREFIXES) { "Too many transmitted prefixes" }
        val prefixLcpModel = AdaptiveModel(16)
        val prefixRemainderCodec = PrefixRemainderCodec()
        val prefixes = ArrayList<String>(prefixCount)
        var previousPrefix = ""
        repeat(prefixCount) {
            val shared = decodeLcp(decoder, prefixLcpModel)
            require(shared <= previousPrefix.length) { "Invalid prefix LCP" }
            val prefix = previousPrefix.substring(0, shared) +
                prefixRemainderCodec.decode(decoder, MAX_PREFIX_CHARS)
            require(prefix.length <= MAX_PREFIX_CHARS) { "Suffix prefix is too long" }
            require(prefix.all(::isAsciiLetter)) { "Invalid suffix-prefix character" }
            require(prefixes.isEmpty() || compareUtf8(previousPrefix, prefix) < 0) {
                "Suffix prefixes are not strictly ascending"
            }
            budget.spend(prefix.length)
            prefixes += prefix
            previousPrefix = prefix
        }

        val literalPresenceModel = AdaptiveModel(2)
        val runCountModel = AdaptiveModel(8)
        val runPrefixModel = AdaptiveModel(maxOf(1, prefixCount))
        val shapes = ArrayList<DecodedSetShape>(setCount)
        for (setIndex in 0 until setCount) {
            val setIdentityCount = setCounts[setIndex]
            val hasLiterals = literalPresenceModel.decode(decoder) == 1
            val literalCount = if (hasLiterals) decoder.gamma() else 0
            require(literalCount <= setIdentityCount) {
                "Literal suffix count exceeds set identity count"
            }

            val runSymbol = runCountModel.decode(decoder)
            val runCount = if (runSymbol < RUN_COUNT_ESCAPE) {
                runSymbol
            } else {
                safeAdd(decoder.gamma(), RUN_COUNT_ESCAPE - 1)
            }
            require(runCount <= MAX_RUNS_PER_SET) { "Too many numeric runs in a set" }
            if (runCount > 0) require(prefixCount > 0) { "Numeric runs have no prefixes" }

            val runPrefixes = ArrayList<String>(runCount)
            repeat(runCount) {
                val index = runPrefixModel.decode(decoder)
                require(index < prefixCount) { "Numeric run prefix index is invalid" }
                val prefix = prefixes[index]
                require(runPrefixes.isEmpty() || compareUtf8(runPrefixes.last(), prefix) < 0) {
                    "Numeric run prefixes are not strictly ascending"
                }
                runPrefixes += prefix
            }
            val numericCount = setIdentityCount - literalCount
            val runCounts = when {
                runCount == 0 -> {
                    require(numericCount == 0) { "Numeric identities have no run" }
                    emptyList()
                }
                else -> {
                    require(numericCount >= runCount) {
                        "Numeric run count exceeds numeric identities"
                    }
                    decodePositiveComposition(decoder, numericCount, runCount)
                }
            }
            shapes += DecodedSetShape(
                setId = setIds[setIndex],
                literalCount = literalCount,
                runs = runPrefixes.zip(runCounts)
            )
        }

        val paddingModel = AdaptiveModel(2)
        val orderedCardIds = ArrayList<String>(identityCount)
        shapes.forEach { shape ->
            shape.runs.forEach { (prefix, count) ->
                var previousNumber = 0
                repeat(count) { index ->
                    val number = if (index == 0) {
                        decoder.rice(FIRST_NUMBER_RICE_PARAMETER) - 1
                    } else {
                        safeAdd(previousNumber, decoder.rice(GAP_RICE_PARAMETER))
                    }
                    require(index == 0 || number > previousNumber) {
                        "Numeric suffixes are not strictly ascending"
                    }
                    previousNumber = number
                    var width = number.toString().length
                    if (paddingModel.decode(decoder) == 1) {
                        width = safeAdd(width, decoder.gamma())
                    }
                    require(width in 1..MAX_NUMERIC_DIGITS) {
                        "Numeric suffix width is invalid"
                    }
                    val digits = number.toString().padStart(width, '0')
                    val cardId = "${shape.setId}-$prefix$digits"
                    budget.spend(cardId.length)
                    orderedCardIds += cardId
                }
            }
            repeat(shape.literalCount) {
                val suffix = decoder.utf8(MAX_ID_BYTES)
                val cardId = "${shape.setId}-$suffix"
                budget.spend(cardId.length)
                orderedCardIds += cardId
            }
        }
        require(orderedCardIds.size == identityCount) { "Identity count mismatch" }
        require(orderedCardIds.toSet().size == orderedCardIds.size) {
            "Duplicate card identity in adaptive payload"
        }
        return orderedCardIds
    }

    // ---------------------------------------------------------------- identities: generic

    private fun encodeGenericIdentities(
        encoder: ArithmeticEncoder,
        cardIds: List<String>
    ): List<String> {
        val ordered = ShareSorting.sortCardIdsByUtf8Ordinal(cardIds)
        val byteModel = AdaptiveModel(256)
        var previous = ByteArray(0)
        ordered.forEach { cardId ->
            val bytes = encodeCanonicalUtf8(cardId)
            require(bytes.size <= MAX_ID_BYTES) { "Card id is too long" }
            val shared = commonPrefixLength(previous, bytes)
            encoder.gamma(shared + 1)
            val remainder = bytes.copyOfRange(shared, bytes.size)
            encoder.gamma(remainder.size + 1)
            remainder.forEach { byteModel.encode(encoder, it.toInt() and 0xFF) }
            previous = bytes
        }
        return ordered
    }

    private fun decodeGenericIdentities(
        decoder: ArithmeticDecoder,
        identityCount: Int,
        budget: DecodeBudget
    ): List<String> {
        val byteModel = AdaptiveModel(256)
        var previous = ByteArray(0)
        val result = ArrayList<String>(identityCount)
        repeat(identityCount) {
            val shared = decoder.gamma() - 1
            require(shared <= previous.size) { "Invalid generic card-id LCP" }
            val remainderSize = decoder.gamma() - 1
            require(shared + remainderSize <= MAX_ID_BYTES) { "Card id is too long" }
            budget.spend(shared + remainderSize)
            val bytes = ByteArray(shared + remainderSize)
            previous.copyInto(bytes, endIndex = shared)
            for (index in shared until bytes.size) {
                bytes[index] = byteModel.decode(decoder).toByte()
            }
            val cardId = decodeCanonicalUtf8(bytes)
            require(cardId.isNotBlank()) { "Card id must not be blank" }
            require(result.isEmpty() || compareUnsigned(previous, bytes) < 0) {
                "Generic card ids are not strictly ascending"
            }
            result += cardId
            previous = bytes
        }
        return result
    }

    // ---------------------------------------------------------------- physical rows

    private fun encodePhysicalRows(
        encoder: ArithmeticEncoder,
        rowCounts: List<Int>,
        rows: List<MirorShareEntry>
    ) {
        val multipleVariants = rowCounts.any { it != 1 }
        encoder.uniformBit(if (multipleVariants) 1 else 0)
        if (multipleVariants) {
            encoder.gamma(rows.size - rowCounts.size + 1)
            encodePositiveComposition(encoder, rowCounts)
        }

        val printingModel = AdaptiveModel(PRINTING_VALUES.size + 1)
        rows.forEach { row ->
            val symbol = PRINTING_VALUES.indexOf(row.variant.printingType)
                .let { if (it >= 0) it else PRINTING_VALUES.size }
            printingModel.encode(encoder, symbol)
            if (symbol == PRINTING_VALUES.size) encoder.utf8(row.variant.printingType)
        }

        val conditionModel = AdaptiveModel(CONDITION_VALUES.size + 1)
        rows.forEach { row ->
            val symbol = CONDITION_VALUES.indexOf(row.condition.rawCondition)
                .let { if (it >= 0) it else CONDITION_VALUES.size }
            conditionModel.encode(encoder, symbol)
            if (symbol == CONDITION_VALUES.size) {
                encoder.utf8(requireNotNull(row.condition.rawCondition))
            }
        }

        encodeSparsePositions(
            encoder,
            rows.size,
            rows.indices.filter { rows[it].variant.isFirstEdition }
        )

        val gradedPositions = rows.indices.filter { rows[it].condition.isGraded }
        encodeSparsePositions(encoder, rows.size, gradedPositions)
        val gradePresenceModel = AdaptiveModel(2)
        gradedPositions.forEach { index ->
            val score = rows[index].condition.gradeScoreTenths
            gradePresenceModel.encode(encoder, if (score == null) 0 else 1)
            if (score != null) encoder.gamma(score + 1)
        }

        val quantityPositions = rows.indices.filter { rows[it].quantity != 1 }
        encodeSparsePositions(encoder, rows.size, quantityPositions)
        quantityPositions.forEach { index -> encoder.gamma(rows[index].quantity - 1) }

        val tagPositions = rows.indices.filter { rows[it].variant.variantTags.isNotEmpty() }
        encodeSparsePositions(encoder, rows.size, tagPositions)
        tagPositions.forEach { index ->
            val tags = rows[index].variant.variantTags
            encoder.gamma(tags.size)
            tags.forEach(encoder::utf8)
        }
    }

    private fun decodePhysicalRows(
        decoder: ArithmeticDecoder,
        cardIds: List<String>
    ): List<MirorShareEntry> {
        val identityCount = cardIds.size
        require(identityCount <= MAX_PHYSICAL_ROWS) { "Too many physical rows" }
        val multipleVariants = decoder.uniformBit() == 1
        val rowCounts: List<Int>
        val rowCount: Int
        if (multipleVariants) {
            require(identityCount > 0) { "Empty identity list has physical variants" }
            val extraRows = decoder.gamma() - 1
            rowCount = safeAdd(identityCount, extraRows)
            require(rowCount in identityCount..MAX_PHYSICAL_ROWS) { "Too many physical rows" }
            rowCounts = decodePositiveComposition(decoder, rowCount, identityCount)
            require(rowCounts.any { it != 1 }) { "Noncanonical physical row composition" }
        } else {
            rowCount = identityCount
            rowCounts = List(identityCount) { 1 }
        }

        val rowCardIds = ArrayList<String>(rowCount)
        cardIds.zip(rowCounts).forEach { (cardId, count) ->
            repeat(count) { rowCardIds += cardId }
        }

        val printingModel = AdaptiveModel(PRINTING_VALUES.size + 1)
        val printings = List(rowCount) {
            val symbol = printingModel.decode(decoder)
            if (symbol < PRINTING_VALUES.size) {
                PRINTING_VALUES[symbol]
            } else {
                decoder.utf8(MAX_VALUE_BYTES)
            }
        }

        val conditionModel = AdaptiveModel(CONDITION_VALUES.size + 1)
        val conditions = List<String?>(rowCount) {
            val symbol = conditionModel.decode(decoder)
            if (symbol < CONDITION_VALUES.size) {
                CONDITION_VALUES[symbol]
            } else {
                decoder.utf8(MAX_VALUE_BYTES)
            }
        }

        val firstPositions = decodeSparsePositions(decoder, rowCount).toSet()
        val gradedList = decodeSparsePositions(decoder, rowCount)
        val gradedPositions = gradedList.toSet()
        val gradeScores = mutableMapOf<Int, Int?>()
        val gradePresenceModel = AdaptiveModel(2)
        gradedList.forEach { index ->
            gradeScores[index] = if (gradePresenceModel.decode(decoder) == 1) {
                val value = decoder.gamma() - 1
                require(value in 0..100) { "Grade score is out of range" }
                value
            } else {
                null
            }
        }

        val quantityList = decodeSparsePositions(decoder, rowCount)
        val quantities = mutableMapOf<Int, Int>()
        quantityList.forEach { index ->
            val quantity = safeAdd(decoder.gamma(), 1)
            require(quantity > 1) { "Invalid shared quantity" }
            quantities[index] = quantity
        }

        val tagList = decodeSparsePositions(decoder, rowCount)
        val tags = mutableMapOf<Int, List<String>>()
        var totalTagCount = 0
        tagList.forEach { index ->
            val count = decoder.gamma()
            require(count in 1..MAX_TAGS_PER_ROW) { "Invalid variant tag count" }
            totalTagCount = safeAdd(totalTagCount, count)
            require(totalTagCount <= MAX_TOTAL_TAGS) { "Too many variant tags" }
            val values = List(count) { decoder.utf8(MAX_VALUE_BYTES) }
            require(values == values.distinct().sortedWith(::compareUtf8)) {
                "Variant tags are not canonical"
            }
            tags[index] = values
        }

        return List(rowCount) { index ->
            MirorShareEntry(
                cardId = rowCardIds[index],
                quantity = quantities[index] ?: 1,
                variant = MirorPhysicalVariant(
                    printingType = printings[index],
                    isFirstEdition = index in firstPositions,
                    variantTags = tags[index].orEmpty()
                ),
                condition = MirorCondition(
                    isGraded = index in gradedPositions,
                    gradeScoreTenths = gradeScores[index],
                    rawCondition = conditions[index]
                )
            )
        }
    }

    // ---------------------------------------------------------------- planning / normalization

    private fun normalizeRows(entries: List<MirorShareEntry>): List<MirorShareEntry> {
        val quantities = linkedMapOf<PhysicalKey, Long>()
        entries.forEach { entry ->
            require(encodeCanonicalUtf8(entry.cardId).size <= MAX_ID_BYTES) {
                "Card id is too long"
            }
            require(entry.quantity > 0) { "Share quantity must be positive" }
            require(entry.condition.isGraded || entry.condition.gradeScoreTenths == null) {
                "A grade score requires a graded entry"
            }
            val tags = entry.variant.variantTags
                .distinct()
                .sortedWith(::compareUtf8)
            require(tags.size <= MAX_TAGS_PER_ROW) { "Too many variant tags on one row" }
            tags.forEach {
                require(encodeCanonicalUtf8(it).size <= MAX_VALUE_BYTES) {
                    "Variant tag is too long"
                }
            }
            require(encodeCanonicalUtf8(entry.variant.printingType).size <= MAX_VALUE_BYTES) {
                "Printing type is too long"
            }
            entry.condition.rawCondition?.let {
                require(encodeCanonicalUtf8(it).size <= MAX_VALUE_BYTES) {
                    "Condition is too long"
                }
            }
            val key = PhysicalKey(
                cardId = entry.cardId,
                printing = entry.variant.printingType,
                firstEdition = entry.variant.isFirstEdition,
                graded = entry.condition.isGraded,
                gradeTenths = entry.condition.gradeScoreTenths,
                condition = entry.condition.rawCondition,
                tags = tags
            )
            val total = (quantities[key] ?: 0L) + entry.quantity.toLong()
            require(total <= Int.MAX_VALUE) { "Aggregated share quantity overflows Int" }
            quantities[key] = total
        }
        require(quantities.size <= MAX_PHYSICAL_ROWS) { "Too many physical share rows" }
        return quantities.map { (key, quantity) ->
            MirorShareEntry(
                cardId = key.cardId,
                quantity = quantity.toInt(),
                variant = MirorPhysicalVariant(key.printing, key.firstEdition, key.tags),
                condition = MirorCondition(key.graded, key.gradeTenths, key.condition)
            )
        }.also { normalized ->
            require(normalized.sumOf { it.variant.variantTags.size.toLong() } <= MAX_TOTAL_TAGS) {
                "Too many variant tags"
            }
        }.sortedWith(ENTRY_COMPARATOR)
    }

    private fun buildGroupedPlan(cardIds: List<String>): GroupedPlan {
        require(cardIds.size <= MAX_IDENTITIES) { "Too many grouped identities" }
        val bySet = linkedMapOf<String, MutableList<String>>()
        cardIds.forEach { cardId ->
            val separator = cardId.indexOf('-')
            require(separator > 0) { "Grouped card id has no set separator" }
            val setId = cardId.substring(0, separator)
            require(setId.length <= MAX_SET_ID_CHARS && setId.all(::isSetIdCharacter)) {
                "Grouped card id has an invalid set id"
            }
            bySet.getOrPut(setId) { mutableListOf() } += cardId
        }
        require(bySet.size <= MAX_SETS) { "Too many grouped sets" }

        val setPlans = bySet.keys.sortedWith(::compareUtf8).map { setId ->
            val ids = bySet.getValue(setId).sortedWith(::compareUtf8)
            classifySet(setId, ids)
        }
        val prefixes = setPlans.flatMap { set -> set.runs.map { it.prefix } }
            .distinct()
            .sortedWith(::compareUtf8)
        require(prefixes.size <= MAX_PREFIXES) { "Too many grouped prefixes" }
        return GroupedPlan(setPlans, prefixes)
    }

    private fun isGroupableCardId(cardId: String): Boolean {
        val separator = cardId.indexOf('-')
        if (separator <= 0) return false
        return cardId.substring(0, separator).all(::isSetIdCharacter)
    }

    private fun classifySet(setId: String, cardIds: List<String>): SetPlan {
        val parsed = mutableListOf<ParsedNumeric>()
        val literals = mutableListOf<String>()
        val keyCounts = mutableMapOf<Pair<String, Int>, Int>()
        cardIds.forEach { cardId ->
            val suffix = cardId.substring(setId.length + 1)
            val numeric = parseNumericSuffix(suffix)
            if (numeric == null) {
                literals += suffix
            } else {
                parsed += numeric
                val key = numeric.prefix to numeric.number
                keyCounts[key] = (keyCounts[key] ?: 0) + 1
            }
        }

        val candidates = linkedMapOf<String, MutableList<ParsedNumeric>>()
        parsed.forEach { value ->
            if (keyCounts.getValue(value.prefix to value.number) > 1) {
                literals += value.suffix
            } else {
                candidates.getOrPut(value.prefix) { mutableListOf() } += value
            }
        }

        val runs = mutableListOf<NumericRun>()
        candidates.keys.sortedWith(::compareUtf8).forEach { prefix ->
            val values = candidates.getValue(prefix).sortedBy { it.number }
            val riceSafe = values.withIndex().all { (index, value) ->
                val encoded = if (index == 0) {
                    value.number
                } else {
                    value.number - values[index - 1].number - 1
                }
                encoded >= 0 &&
                    (encoded ushr if (index == 0) {
                        FIRST_NUMBER_RICE_PARAMETER
                    } else {
                        GAP_RICE_PARAMETER
                    }) <= MAX_RICE_QUOTIENT
            }
            if (riceSafe) {
                runs += NumericRun(prefix, values)
            } else {
                values.forEach { literals += it.suffix }
            }
        }
        require(runs.size <= MAX_RUNS_PER_SET) { "Too many numeric runs in one set" }
        return SetPlan(
            setId = setId,
            identityCount = cardIds.size,
            runs = runs,
            literals = literals.sortedWith(::compareUtf8)
        )
    }

    private fun parseNumericSuffix(suffix: String): ParsedNumeric? {
        if (suffix.isEmpty()) return null
        var digitStart = 0
        while (digitStart < suffix.length && isAsciiLetter(suffix[digitStart])) digitStart++
        if (digitStart == suffix.length) return null
        val digits = suffix.substring(digitStart)
        if (digits.length > MAX_NUMERIC_DIGITS || digits.any { it !in '0'..'9' }) return null
        var number = 0L
        digits.forEach { char ->
            number = number * 10 + (char - '0')
            if (number > Int.MAX_VALUE) return null
        }
        return ParsedNumeric(
            prefix = suffix.substring(0, digitStart),
            number = number.toInt(),
            digits = digits,
            suffix = suffix
        )
    }

    // ---------------------------------------------------------------- entropy helpers

    private fun encodeLcp(encoder: ArithmeticEncoder, model: AdaptiveModel, shared: Int) {
        if (shared < LCP_ESCAPE) {
            model.encode(encoder, shared)
        } else {
            model.encode(encoder, LCP_ESCAPE)
            encoder.gamma(shared - (LCP_ESCAPE - 1))
        }
    }

    private fun decodeLcp(decoder: ArithmeticDecoder, model: AdaptiveModel): Int {
        val symbol = model.decode(decoder)
        return if (symbol < LCP_ESCAPE) {
            symbol
        } else {
            safeAdd(decoder.gamma(), LCP_ESCAPE - 1)
        }
    }

    private fun encodePositiveComposition(encoder: ArithmeticEncoder, counts: List<Int>) {
        require(counts.isNotEmpty() && counts.all { it > 0 }) {
            "Positive composition requires positive parts"
        }
        if (counts.size == 1) return
        val total = counts.sum()
        val positions = ArrayList<Int>(counts.size - 1)
        var cumulative = 0
        counts.dropLast(1).forEach { count ->
            cumulative += count
            positions += cumulative - 1
        }
        encodeSubset(encoder, total - 1, positions)
    }

    private fun decodePositiveComposition(
        decoder: ArithmeticDecoder,
        totalItems: Int,
        partCount: Int
    ): List<Int> {
        require(partCount > 0 && totalItems >= partCount) {
            "Invalid positive composition shape"
        }
        if (partCount == 1) return listOf(totalItems)
        val positions = decodeSubset(decoder, totalItems - 1, partCount - 1)
        val result = ArrayList<Int>(partCount)
        var previousBoundary = 0
        positions.forEach { position ->
            val boundary = position + 1
            result += boundary - previousBoundary
            previousBoundary = boundary
        }
        result += totalItems - previousBoundary
        require(result.all { it > 0 }) { "Decoded a non-positive composition part" }
        return result
    }

    private fun encodeSparsePositions(
        encoder: ArithmeticEncoder,
        totalItems: Int,
        positions: List<Int>
    ) {
        encoder.gamma(positions.size + 1)
        encodeSubset(encoder, totalItems, positions)
    }

    private fun decodeSparsePositions(
        decoder: ArithmeticDecoder,
        totalItems: Int
    ): List<Int> {
        val count = decoder.gamma() - 1
        require(count <= totalItems) { "Sparse position count is invalid" }
        return decodeSubset(decoder, totalItems, count)
    }

    /**
     * Encodes a k-of-n bitmap directly with hypergeometric arithmetic frequencies.
     * This has the same information cost as an enumerative rank without BigInteger.
     */
    private fun encodeSubset(
        encoder: ArithmeticEncoder,
        totalItems: Int,
        selectedPositions: List<Int>
    ) {
        require(totalItems in 0..MAX_SUBSET_ITEMS) { "Subset domain is too large" }
        require(selectedPositions == selectedPositions.distinct().sorted()) {
            "Subset positions must be unique and ascending"
        }
        require(selectedPositions.all { it in 0 until totalItems }) {
            "Subset position is outside its domain"
        }
        var selectedIndex = 0
        var remainingSelected = selectedPositions.size
        for (position in 0 until totalItems) {
            val remainingSlots = totalItems - position
            if (remainingSelected == 0 || remainingSelected == remainingSlots) break
            val selected = selectedIndex < selectedPositions.size &&
                selectedPositions[selectedIndex] == position
            if (selected) {
                encoder.encode(0, remainingSelected, remainingSlots)
                selectedIndex++
                remainingSelected--
            } else {
                encoder.encode(remainingSelected, remainingSlots, remainingSlots)
            }
        }
        require(selectedIndex + remainingSelected == selectedPositions.size)
    }

    private fun decodeSubset(
        decoder: ArithmeticDecoder,
        totalItems: Int,
        selectedCount: Int
    ): List<Int> {
        require(totalItems in 0..MAX_SUBSET_ITEMS) { "Subset domain is too large" }
        require(selectedCount in 0..totalItems) { "Subset size is invalid" }
        val result = ArrayList<Int>(selectedCount)
        var remainingSelected = selectedCount
        for (position in 0 until totalItems) {
            val remainingSlots = totalItems - position
            when {
                remainingSelected == 0 -> break
                remainingSelected == remainingSlots -> {
                    for (forced in position until totalItems) result += forced
                    remainingSelected = 0
                    break
                }
                else -> {
                    val target = decoder.scaledValue(remainingSlots)
                    if (target < remainingSelected) {
                        decoder.consume(0, remainingSelected, remainingSlots)
                        result += position
                        remainingSelected--
                    } else {
                        decoder.consume(remainingSelected, remainingSlots, remainingSlots)
                    }
                }
            }
        }
        require(remainingSelected == 0 && result.size == selectedCount) {
            "Subset did not decode completely"
        }
        return result
    }

    private class SetRemainderCodec {
        private val classModels = List(4) { AdaptiveModel(4) }
        private val lowerModel = AdaptiveModel(26)
        private val digitModel = AdaptiveModel(10)

        fun encode(encoder: ArithmeticEncoder, value: String) {
            var context = SET_CLASS_START
            value.forEach { char ->
                val charClass = when {
                    char in 'a'..'z' -> SET_CLASS_LOWER
                    char in '0'..'9' -> SET_CLASS_DIGIT
                    char == '.' -> SET_CLASS_DOT
                    else -> throw IllegalArgumentException("Unsupported set-id character")
                }
                classModels[context].encode(encoder, charClass)
                when (charClass) {
                    SET_CLASS_LOWER -> lowerModel.encode(encoder, char - 'a')
                    SET_CLASS_DIGIT -> digitModel.encode(encoder, char - '0')
                }
                context = charClass
            }
            classModels[context].encode(encoder, SET_CLASS_END)
        }

        fun decode(decoder: ArithmeticDecoder, maxChars: Int): String {
            val output = StringBuilder()
            var context = SET_CLASS_START
            while (true) {
                val charClass = classModels[context].decode(decoder)
                when (charClass) {
                    SET_CLASS_END -> return output.toString()
                    SET_CLASS_LOWER -> output.append(('a'.code + lowerModel.decode(decoder)).toChar())
                    SET_CLASS_DIGIT -> output.append(('0'.code + digitModel.decode(decoder)).toChar())
                    SET_CLASS_DOT -> output.append('.')
                    else -> throw IllegalArgumentException("Invalid set-id class")
                }
                require(output.length <= maxChars) { "Set id is too long" }
                context = charClass
            }
        }
    }

    private class PrefixRemainderCodec {
        private val classModels = List(4) { AdaptiveModel(3) }
        private val upperModel = AdaptiveModel(26)
        private val lowerModel = AdaptiveModel(26)

        fun encode(encoder: ArithmeticEncoder, value: String) {
            var context = PREFIX_CLASS_START
            value.forEach { char ->
                val charClass: Int
                val symbol: Int
                if (char in 'A'..'Z') {
                    charClass = PREFIX_CLASS_UPPER
                    symbol = char - 'A'
                } else if (char in 'a'..'z') {
                    charClass = PREFIX_CLASS_LOWER
                    symbol = char - 'a'
                } else {
                    throw IllegalArgumentException("Unsupported suffix-prefix character")
                }
                classModels[context].encode(encoder, charClass)
                (if (charClass == PREFIX_CLASS_UPPER) upperModel else lowerModel)
                    .encode(encoder, symbol)
                context = charClass
            }
            classModels[context].encode(encoder, PREFIX_CLASS_END)
        }

        fun decode(decoder: ArithmeticDecoder, maxChars: Int): String {
            val output = StringBuilder()
            var context = PREFIX_CLASS_START
            while (true) {
                val charClass = classModels[context].decode(decoder)
                when (charClass) {
                    PREFIX_CLASS_END -> return output.toString()
                    PREFIX_CLASS_UPPER ->
                        output.append(('A'.code + upperModel.decode(decoder)).toChar())
                    PREFIX_CLASS_LOWER ->
                        output.append(('a'.code + lowerModel.decode(decoder)).toChar())
                    else -> throw IllegalArgumentException("Invalid suffix-prefix class")
                }
                require(output.length <= maxChars) { "Suffix prefix is too long" }
                context = charClass
            }
        }
    }

    private class AdaptiveModel(size: Int) {
        private var counts = IntArray(size) { 1 }
        private var total = size

        init {
            require(size > 0 && size < ADAPTIVE_RESCALE_TOTAL)
        }

        fun encode(encoder: ArithmeticEncoder, symbol: Int) {
            require(symbol in counts.indices) { "Adaptive symbol is out of range" }
            var low = 0
            for (index in 0 until symbol) low += counts[index]
            encoder.encode(low, low + counts[symbol], total)
            increment(symbol)
        }

        fun decode(decoder: ArithmeticDecoder): Int {
            val target = decoder.scaledValue(total)
            var cumulative = 0
            for (symbol in counts.indices) {
                val high = cumulative + counts[symbol]
                if (target < high) {
                    decoder.consume(cumulative, high, total)
                    increment(symbol)
                    return symbol
                }
                cumulative = high
            }
            throw IllegalArgumentException("Adaptive symbol is out of range")
        }

        private fun increment(symbol: Int) {
            counts[symbol]++
            total++
            if (total >= ADAPTIVE_RESCALE_TOTAL) {
                total = 0
                for (index in counts.indices) {
                    counts[index] = (counts[index] + 1) / 2
                    total += counts[index]
                }
            }
        }
    }

    private class ArithmeticEncoder {
        private var low = 0uL
        private var high = ARITHMETIC_MASK
        private var pending = 0
        private val output = BitOutput()

        fun encode(cumulativeLow: Int, cumulativeHigh: Int, total: Int) {
            require(cumulativeLow >= 0 && cumulativeLow < cumulativeHigh && cumulativeHigh <= total)
            require(total in 1..MAX_ARITHMETIC_TOTAL) { "Arithmetic total is out of range" }
            val span = high - low + 1u
            high = low + (span * cumulativeHigh.toULong() / total.toULong()) - 1u
            low += span * cumulativeLow.toULong() / total.toULong()

            while (true) {
                when {
                    high < ARITHMETIC_HALF -> emitWithPending(0)
                    low >= ARITHMETIC_HALF -> {
                        emitWithPending(1)
                        low -= ARITHMETIC_HALF
                        high -= ARITHMETIC_HALF
                    }
                    low >= ARITHMETIC_QUARTER && high < ARITHMETIC_THREE_QUARTER -> {
                        pending++
                        require(pending <= MAX_PENDING_BITS) { "Arithmetic pending run is too long" }
                        low -= ARITHMETIC_QUARTER
                        high -= ARITHMETIC_QUARTER
                    }
                    else -> break
                }
                low = (low shl 1) and ARITHMETIC_MASK
                high = ((high shl 1) and ARITHMETIC_MASK) or 1u
            }
        }

        fun uniformBit(bit: Int) {
            require(bit == 0 || bit == 1)
            encode(bit, bit + 1, 2)
        }

        fun fixed(value: Int, width: Int) {
            require(width in 0..31)
            if (width < 31) {
                require(value >= 0 && (width == 0 && value == 0 || value < (1 shl width)))
            } else {
                require(value >= 0)
            }
            for (shift in width - 1 downTo 0) uniformBit((value ushr shift) and 1)
        }

        fun gamma(value: Int) {
            require(value >= 1)
            val width = bitLength(value)
            repeat(width - 1) { uniformBit(0) }
            uniformBit(1)
            if (width > 1) fixed(value xor (1 shl (width - 1)), width - 1)
        }

        fun rice(value: Int, parameter: Int) {
            require(value >= 1 && parameter in 0..30)
            val encoded = value - 1
            val quotient = encoded ushr parameter
            require(quotient <= MAX_RICE_QUOTIENT) { "Rice quotient is too long" }
            repeat(quotient) { uniformBit(0) }
            uniformBit(1)
            if (parameter > 0) fixed(encoded and ((1 shl parameter) - 1), parameter)
        }

        fun utf8(value: String) {
            val bytes = encodeCanonicalUtf8(value)
            require(bytes.size <= MAX_VALUE_BYTES) { "UTF-8 value is too long" }
            gamma(bytes.size + 1)
            bytes.forEach { fixed(it.toInt() and 0xFF, 8) }
        }

        fun finish(): ByteArray {
            pending++
            if (low < ARITHMETIC_QUARTER) emitWithPending(0) else emitWithPending(1)
            return output.toByteArray()
        }

        private fun emitWithPending(bit: Int) {
            output.write(bit)
            val opposite = bit xor 1
            while (pending > 0) {
                output.write(opposite)
                pending--
            }
        }
    }

    private class ArithmeticDecoder(data: ByteArray) {
        private val input = BitInput(data)
        private var low = 0uL
        private var high = ARITHMETIC_MASK
        private var code = 0uL

        init {
            repeat(ARITHMETIC_STATE_BITS) {
                code = ((code shl 1) and ARITHMETIC_MASK) or input.read().toULong()
            }
        }

        fun scaledValue(total: Int): Int {
            require(total in 1..MAX_ARITHMETIC_TOTAL) { "Arithmetic total is out of range" }
            require(code in low..high) { "Arithmetic code is outside its interval" }
            val span = high - low + 1u
            val scaled = (((code - low + 1u) * total.toULong()) - 1u) / span
            require(scaled < total.toULong()) { "Arithmetic value is out of range" }
            return scaled.toInt()
        }

        fun consume(cumulativeLow: Int, cumulativeHigh: Int, total: Int) {
            require(cumulativeLow >= 0 && cumulativeLow < cumulativeHigh && cumulativeHigh <= total)
            val span = high - low + 1u
            high = low + (span * cumulativeHigh.toULong() / total.toULong()) - 1u
            low += span * cumulativeLow.toULong() / total.toULong()

            while (true) {
                when {
                    high < ARITHMETIC_HALF -> Unit
                    low >= ARITHMETIC_HALF -> {
                        low -= ARITHMETIC_HALF
                        high -= ARITHMETIC_HALF
                        code -= ARITHMETIC_HALF
                    }
                    low >= ARITHMETIC_QUARTER && high < ARITHMETIC_THREE_QUARTER -> {
                        low -= ARITHMETIC_QUARTER
                        high -= ARITHMETIC_QUARTER
                        code -= ARITHMETIC_QUARTER
                    }
                    else -> break
                }
                low = (low shl 1) and ARITHMETIC_MASK
                high = ((high shl 1) and ARITHMETIC_MASK) or 1u
                code = ((code shl 1) and ARITHMETIC_MASK) or input.read().toULong()
            }
        }

        fun uniformBit(): Int {
            val bit = scaledValue(2)
            consume(bit, bit + 1, 2)
            return bit
        }

        fun fixed(width: Int): Int {
            require(width in 0..31)
            var result = 0
            repeat(width) { result = (result shl 1) or uniformBit() }
            return result
        }

        fun gamma(): Int {
            var zeros = 0
            while (uniformBit() == 0) {
                zeros++
                require(zeros <= MAX_GAMMA_ZEROS) { "Gamma value is too long" }
            }
            val suffix = if (zeros == 0) 0 else fixed(zeros)
            val value = (1L shl zeros) or suffix.toLong()
            require(value <= Int.MAX_VALUE) { "Gamma value overflows Int" }
            return value.toInt()
        }

        fun rice(parameter: Int): Int {
            require(parameter in 0..30)
            var quotient = 0
            while (uniformBit() == 0) {
                quotient++
                require(quotient <= MAX_RICE_QUOTIENT) { "Rice quotient is too long" }
            }
            val remainder = if (parameter == 0) 0 else fixed(parameter)
            val value = (quotient.toLong() shl parameter) + remainder + 1L
            require(value <= Int.MAX_VALUE) { "Rice value overflows Int" }
            return value.toInt()
        }

        fun utf8(maxBytes: Int): String {
            val size = gamma() - 1
            require(size <= maxBytes) { "UTF-8 value is too long" }
            val bytes = ByteArray(size) { fixed(8).toByte() }
            return decodeCanonicalUtf8(bytes)
        }
    }

    private class BitOutput {
        private val bytes = mutableListOf<Byte>()
        private var accumulator = 0
        private var bitCount = 0

        fun write(bit: Int) {
            accumulator = (accumulator shl 1) or (bit and 1)
            bitCount++
            if (bitCount == 8) {
                bytes += accumulator.toByte()
                accumulator = 0
                bitCount = 0
            }
        }

        fun toByteArray(): ByteArray {
            if (bitCount > 0) bytes += (accumulator shl (8 - bitCount)).toByte()
            return bytes.toByteArray()
        }
    }

    private class BitInput(private val bytes: ByteArray) {
        private var position = 0
        private var syntheticBits = 0

        fun read(): Int {
            if (position >= bytes.size * 8) {
                syntheticBits++
                require(syntheticBits <= MAX_SYNTHETIC_BITS) {
                    "Arithmetic stream ended unexpectedly"
                }
                return 0
            }
            val result =
                (bytes[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
            position++
            return result
        }
    }

    // ---------------------------------------------------------------- envelope

    /**
     * A single allowance for every identity string a payload may materialize.
     *
     * The per-string caps alone are not enough. Adaptive models saturate near probability
     * one, so a repetitive dictionary costs roughly a thousandth of a bit per character:
     * measured amplification reaches ~10,000x, and 4,096 set ids of [MAX_SET_ID_CHARS]
     * each would expand about 25 KiB of wire data into ~537 MB of UTF-16. A pasted code is
     * bounded to 32,768 characters, which is ~44 KiB of payload -- comfortably enough. The
     * resulting OutOfMemoryError is an Error, not an exception, so it would crash the app
     * rather than be reported as a bad code.
     *
     * Counting characters across every lane bounds that directly. The allowance is ~13x
     * the entire 20,523-card catalog (~308,000 characters of ids), so no plausible share
     * is affected.
     */
    private class DecodeBudget(private var remaining: Int = MAX_TOTAL_IDENTITY_CHARS) {
        fun spend(amount: Int) {
            require(amount >= 0) { "Decoded identity length is negative" }
            require(amount <= remaining) { "Decoded identity data is too large" }
            remaining -= amount
        }
    }

    private class EnvelopeWriter {
        private val bytes = mutableListOf<Byte>()

        fun writeByte(value: Int) {
            bytes += (value and 0xFF).toByte()
        }

        fun writeBytes(value: ByteArray) {
            value.forEach { bytes += it }
        }

        fun writeUleb(value: Int) {
            require(value >= 0)
            var current = value
            do {
                var byte = current and 0x7F
                current = current ushr 7
                if (current != 0) byte = byte or 0x80
                writeByte(byte)
            } while (current != 0)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private class EnvelopeReader(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size - position

        fun readByte(): Int {
            require(remaining > 0) { "Unexpected end of adaptive identity payload" }
            return bytes[position++].toInt() and 0xFF
        }

        fun readBytes(size: Int): ByteArray {
            require(size >= 0 && size <= remaining) {
                "Unexpected end of adaptive identity payload"
            }
            val result = bytes.copyOfRange(position, position + size)
            position += size
            return result
        }

        fun skip(size: Int) {
            require(size >= 0 && size <= remaining) {
                "Unexpected end of adaptive identity payload"
            }
            position += size
        }

        fun readUleb(label: String): Int {
            var value = 0L
            var shift = 0
            var count = 0
            while (count < 5) {
                val byte = readByte()
                count++
                value = value or ((byte and 0x7F).toLong() shl shift)
                if ((byte and 0x80) == 0) {
                    require(value <= Int.MAX_VALUE) { "$label overflows Int" }
                    if (count > 1) {
                        val minimum = 1L shl (7 * (count - 1))
                        require(value >= minimum) { "$label uses noncanonical ULEB128" }
                    }
                    return value.toInt()
                }
                shift += 7
            }
            throw IllegalArgumentException("$label ULEB128 is too long")
        }
    }

    // ---------------------------------------------------------------- data / ordering

    private data class PhysicalKey(
        val cardId: String,
        val printing: String,
        val firstEdition: Boolean,
        val graded: Boolean,
        val gradeTenths: Int?,
        val condition: String?,
        val tags: List<String>
    )

    private data class ParsedNumeric(
        val prefix: String,
        val number: Int,
        val digits: String,
        val suffix: String
    )

    private data class NumericRun(val prefix: String, val values: List<ParsedNumeric>)

    private data class SetPlan(
        val setId: String,
        val identityCount: Int,
        val runs: List<NumericRun>,
        val literals: List<String>
    )

    private data class GroupedPlan(val sets: List<SetPlan>, val prefixes: List<String>)

    private data class DecodedSetShape(
        val setId: String,
        val literalCount: Int,
        val runs: List<Pair<String, Int>>
    )

    private val PRINTING_VALUES = listOf(
        MirorPhysicalVariant.PRINTING_STANDARD,
        MirorPhysicalVariant.PRINTING_HOLO,
        "Rev Holo",
        MirorPhysicalVariant.PRINTING_REVERSE_HOLO
    )

    private val CONDITION_VALUES: List<String?> =
        listOf("NM", "LP", "MP", "HP", "DMG", null)

    private val PHYSICAL_ROW_COMPARATOR = Comparator<MirorShareEntry> { left, right ->
        comparePhysical(left, right)
    }

    private val ENTRY_COMPARATOR = Comparator<MirorShareEntry> { left, right ->
        val card = compareUtf8(left.cardId, right.cardId)
        if (card != 0) card else {
            val physical = comparePhysical(left, right)
            if (physical != 0) physical else left.quantity.compareTo(right.quantity)
        }
    }

    private fun comparePhysical(left: MirorShareEntry, right: MirorShareEntry): Int {
        compareUtf8(left.variant.printingType, right.variant.printingType).let {
            if (it != 0) return it
        }
        left.variant.isFirstEdition.compareTo(right.variant.isFirstEdition).let {
            if (it != 0) return it
        }
        left.condition.isGraded.compareTo(right.condition.isGraded).let {
            if (it != 0) return it
        }
        compareNullableInt(left.condition.gradeScoreTenths, right.condition.gradeScoreTenths).let {
            if (it != 0) return it
        }
        compareNullableString(left.condition.rawCondition, right.condition.rawCondition).let {
            if (it != 0) return it
        }
        return compareStringLists(left.variant.variantTags, right.variant.variantTags)
    }

    private fun compareNullableInt(left: Int?, right: Int?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
    }

    private fun compareNullableString(left: String?, right: String?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> compareUtf8(left, right)
    }

    private fun compareStringLists(left: List<String>, right: List<String>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = compareUtf8(left[index], right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun compareUtf8(left: String, right: String): Int =
        ShareSorting.compareUtf8Ordinal(left, right)

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison =
                (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        var index = 0
        while (index < minOf(left.length, right.length) && left[index] == right[index]) index++
        return index
    }

    private fun commonPrefixLength(left: ByteArray, right: ByteArray): Int {
        var index = 0
        while (index < minOf(left.size, right.size) && left[index] == right[index]) index++
        return index
    }

    private fun isSetIdCharacter(char: Char): Boolean =
        char in 'a'..'z' || char in '0'..'9' || char == '.'

    private fun isAsciiLetter(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z'

    private fun bitLength(value: Int): Int {
        require(value > 0)
        return 32 - value.countLeadingZeroBits()
    }

    private fun safeAdd(left: Int, right: Int): Int {
        val result = left.toLong() + right.toLong()
        require(result <= Int.MAX_VALUE) { "Integer value overflows Int" }
        return result.toInt()
    }

    private fun decodeCanonicalUtf8(bytes: ByteArray): String {
        val decoded = bytes.decodeToString()
        require(decoded.encodeToByteArray().contentEquals(bytes)) { "Invalid UTF-8 string" }
        return decoded
    }

    /**
     * Kotlin replaces unpaired UTF-16 surrogates while encoding UTF-8. Shared
     * values must remain byte-exact, so reject ill-formed UTF-16 instead.
     */
    private fun encodeCanonicalUtf8(value: String): ByteArray {
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            when {
                code in 0xD800..0xDBFF -> {
                    require(index + 1 < value.length) {
                        "Unpaired high surrogate in shared text"
                    }
                    require(value[index + 1].code in 0xDC00..0xDFFF) {
                        "Unpaired high surrogate in shared text"
                    }
                    index += 2
                }
                code in 0xDC00..0xDFFF -> {
                    throw IllegalArgumentException("Unpaired low surrogate in shared text")
                }
                else -> index++
            }
        }
        return value.encodeToByteArray()
    }

    // ---------------------------------------------------------------- frozen constants

    private const val FIXED_HEADER_SIZE = 4
    private const val ENVELOPE_VERSION = 1
    private const val SHARE_TYPE_COLLECTION = 1
    private val MAGIC_M = 'M'.code.toByte()
    private val MAGIC_S = 'S'.code.toByte()

    private const val FIRST_NUMBER_RICE_PARAMETER = 6
    private const val GAP_RICE_PARAMETER = 4
    private const val RUN_COUNT_ESCAPE = 7
    private const val LCP_ESCAPE = 15

    private const val SET_CLASS_LOWER = 0
    private const val SET_CLASS_DIGIT = 1
    private const val SET_CLASS_DOT = 2
    private const val SET_CLASS_END = 3
    private const val SET_CLASS_START = 3

    private const val PREFIX_CLASS_UPPER = 0
    private const val PREFIX_CLASS_LOWER = 1
    private const val PREFIX_CLASS_END = 2
    private const val PREFIX_CLASS_START = 3

    private const val ADAPTIVE_RESCALE_TOTAL = 1 shl 15
    private const val ARITHMETIC_STATE_BITS = 32
    private const val ARITHMETIC_MASK = 0xFFFF_FFFFuL
    private const val ARITHMETIC_HALF = 0x8000_0000uL
    private const val ARITHMETIC_QUARTER = 0x4000_0000uL
    private const val ARITHMETIC_THREE_QUARTER = 0xC000_0000uL
    private const val MAX_ARITHMETIC_TOTAL = 1 shl 20

    private const val MAX_SOURCE_ROWS = 1_000_000
    // Repeated owned copies aggregate into quantity, so this bounds distinct physical
    // variants rather than collection size. Keep it above three times today's complete
    // catalog while rejecting adaptive-model row amplification before large allocations.
    private const val MAX_PHYSICAL_ROWS = 65_535
    private const val MAX_IDENTITIES = MAX_PHYSICAL_ROWS
    private const val MAX_SUBSET_ITEMS = MAX_PHYSICAL_ROWS
    private const val MAX_SETS = 4_096
    private const val MAX_PREFIXES = 4_096
    private const val MAX_RUNS_PER_SET = 4_096
    // Known, unresolved, and unreachable with real data: a set-id remainder of *exactly*
    // 65,536 characters is accepted by the Python reference but rejected here with "Set id
    // is too long". Narrowed as far as it has been: the coders themselves agree at scale
    // -- 61 consecutive ids of 65,000 characters (~4M chars, millions of adaptive-model
    // updates and many rescales) decode identically on both sides -- and the two rescale
    // routines are equivalent. Only this one boundary value differs, and no real set id
    // exceeds 11 characters. It fails closed on both sides, so no wrong card can result.
    private const val MAX_SET_ID_CHARS = 65_536
    private const val MAX_PREFIX_CHARS = 65_536
    private const val MAX_NUMERIC_DIGITS = 10
    private const val MAX_ID_BYTES = 65_536
    private const val MAX_TOTAL_IDENTITY_CHARS = 4_000_000
    private const val MAX_VALUE_BYTES = 65_536
    private const val MAX_TAGS_PER_ROW = 1_024
    private const val MAX_TOTAL_TAGS = 100_000
    // Internal rather than private so [ShareManifestBinaryCodec.MAX_MANIFEST_BYTES] can
    // republish exactly this number. Miror Link needs the same ceiling to bound its
    // reassembly buffer, and a second copy of the literal would be free to drift.
    internal const val MAX_ENVELOPE_BYTES = 4 * 1024 * 1024
    private const val MAX_CORE_BYTES = 2 * 1024 * 1024
    private const val MAX_EXTENSIONS = 256
    private const val MAX_EXTENSION_BYTES = 1024 * 1024
    private const val MAX_TOTAL_EXTENSION_BYTES = 4L * 1024L * 1024L
    private const val MAX_RICE_QUOTIENT = 1_000_000
    private const val MAX_GAMMA_ZEROS = 30
    private const val MAX_SYNTHETIC_BITS = 64
    private const val MAX_PENDING_BITS = 4 * 1024 * 1024
}
