package app.swarsetu.translation

import android.content.Context
import android.util.Log
import app.swarsetu.translation.indictrans.IndicLanguage
import app.swarsetu.translation.indictrans.IndicTrans2Engine
import app.swarsetu.translation.indictrans.IndicTransModelBundle
import app.swarsetu.translation.indictrans.IndicTransModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles on-device machine translation using IndicTrans2 ONNX INT8 models with entity shielding,
 * complex sentence clause routing, and direct Indic-to-Indic translation across 22 Indian languages.
 * Completely replaces legacy Google ML Kit on-device translation.
 */
class TranslatorEngine(
    context: Context? = null,
    modelManager: IndicTransModelManager? = null,
) {
    val modelManager: IndicTransModelManager = modelManager
        ?: IndicTransModelManager(context?.applicationContext ?: context)

    val indicTransEngine = IndicTrans2Engine(this.modelManager)

    /**
     * Normalizes language codes/names ("english", "en", "hindi", "hi", "mr", "gu", "ta", "te", "kn", "bn", "ml", "or")
     * to standard 2-letter ISO language tags.
     */
    fun normalizeToLanguageTag(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        val clean = lang.trim().lowercase().split("-", "_").first()
        val indicLang = IndicLanguage.fromCode(clean)
        return indicLang?.code ?: clean
    }

    private val COMMON_ENGLISH_WORDS = setOf(
        "the", "this", "that", "there", "these", "those", "they", "them", "their", "theirs",
        "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
        "name", "named", "names", "friend", "friends", "live", "living", "lives", "lived", "go", "going", "goes", "went", "gone",
        "come", "coming", "comes", "came", "stay", "staying", "stays", "stayed", "speak", "speaking", "speaks", "spoke",
        "call", "calling", "calls", "called", "meet", "meeting", "meets", "met",
        "please", "yes", "no", "okay", "ok", "good", "hello", "hey", "hi", "welcome", "thanks", "thank",
        "today", "tomorrow", "yesterday", "tonight", "morning", "evening", "afternoon", "night",
        "here", "now", "just", "very", "much", "many", "more", "most", "less", "least",
        "help", "send", "urgent", "danger", "safe", "need", "stop", "start",
        "is", "am", "are", "was", "were", "be", "been", "being", "have", "has", "had", "having",
        "do", "does", "did", "doing", "done", "can", "could", "shall", "should", "will", "would", "may", "might", "must",
        "i", "me", "my", "mine", "myself", "you", "your", "yours", "yourself",
        "he", "him", "his", "himself", "she", "her", "hers", "herself", "it", "its", "itself",
        "we", "us", "our", "ours", "ourselves", "all", "some", "any", "every", "each", "both", "either", "neither",
        "and", "or", "but", "nor", "so", "for", "yet", "because", "although", "though", "since", "unless",
        "if", "then", "else", "when", "while", "where", "after", "before", "until", "as", "than",
        "in", "on", "at", "to", "from", "with", "without", "by", "about", "against", "between", "into",
        "through", "during", "above", "below", "up", "down", "out", "off", "over", "under", "again",
        "near", "inside", "outside", "behind", "front", "beside", "among", "across", "along", "around",
        "towards", "toward", "past", "throughout", "via", "upon", "onto", "within", "beyond", "during",
        "before", "after", "since", "until", "till",
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "first", "second", "third",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "city", "state", "country", "village", "town", "place", "area", "zone", "road", "street", "house", "home",
        "person", "people", "man", "woman", "boy", "girl", "sir", "madam", "team", "group", "room", "water", "food",
    )

    private val COMMON_INDIC_WORDS = setOf(
        "है", "हूँ", "हु", "हूं", "हो", "था", "थी", "थे", "हैं", "का", "की", "के", "को", "से", "में", "पर", "ने", "और", "या", "भी", "तो", "ही", "लिए", "वाला", "वाली", "वाले", "रहा", "रही", "रहे", "सकता", "सकती", "सकते", "चाहिए", "पास", "साथ", "बोल", "कहा", "गया", "गई", "गए", "कर", "करना", "करते", "करता", "करती", "यहाँ", "वहाँ", "कहाँ", "कब", "कैसे", "क्यों", "क्या", "हम", "आप", "तुम", "मेरा", "मेरी", "मेरे", "तुम्हारा", "तुम्हारी", "तुम्हारे", "आपका", "आपकी", "आपके", "इसका", "उसका",
        "आहे", "होतो", "होती", "होते", "आहोत", "नाही", "चा", "ची", "चे", "ला", "ना", "कडून", "वरून", "मध्ये", "वर", "आणि", "किंवा", "पण", "सुद्धा", "बोलतोय", "बोलतेय", "माझे", "माझी", "माझा", "तुमचे", "तुमची", "तुमचा",
        "છે", "હતો", "હતી", "હતા", "છું", "છો", "છીએ", "નથી", "નો", "ની", "નું", "ના", "ને", "થી", "માં", "પર", "અને", "કે", "પણ", "હું", "તમે", "અમે", "તેઓ", "મારું", "મારો", "મારી", "તમારું", "તમારો", "તમારી",
        "হয়", "হলো", "ছিল", "আছে", "এর", "কে", "থেকে", "তে", "এ", "এবং", "বা", "আর", "আমি", "তুমি", "আপনি", "আমার", "তোমার", "আপনার",
        "ଅଟେ", "ଅଛି", "ଥିଲା", "ଏବଂ", "ବା", "ରୁ", "ରେ", "ମୁଁ", "ତୁମେ", "ଆପଣ", "ମୋର", "ତୁମର", "ଆପଣଙ୍କ",
        "இருக்கிறேன்", "இருக்கிறார்", "உள்ளது", "உள்ளார்", "மற்றும்", "அல்லது", "இருந்து", "இல்", "நான்", "நீங்கள்", "என்", "உங்கள்",
        "ఉన్నాను", "ఉన్నారు", "ఉంది", "మరియు", "లేదా", "నుండి", "లో", "నేను", "మీరు", "నా", "మీ",
        "ಇದ್ದೇನೆ", "ಇದ್ದಾರೆ", "ಇದೆ", "ಮತ್ತು", "ಅಥವಾ", "ಇಂದ", "ನಲ್ಲಿ", "ನಾನು", "ನೀವು", "ನನ್ನ", "ನಿಮ್ಮ",
        "ആണ്", "ഉണ്ട്", "ആയിരുന്നു", "ഒപ്പം", "നിന്ന്", "ഇൽ", "ഞാൻ", "നിങ്ങൾ", "എന്റെ", "നിങ്ങളുടെ",
    )

    private val KNOWN_CRITICAL_ENTITIES = listOf(
        "SwarSetu", "iTantra", "ISRO", "NDRF", "SDRF", "AIIMS", "DRDO", "IMD", "SOS", "FIR",
        "New Delhi", "Delhi", "Connaught Place", "Sector 18", "MG Road", "Marine Drive", "Park Street",
        "Salt Lake", "IIT Bombay", "IIT Delhi", "IIT Madras", "IIT Kanpur", "IIT Kharagpur", "IIT Roorkee",
        "Mumbai", "Bengaluru", "Bangalore", "Kolkata", "Chennai", "Hyderabad", "Pune", "Ahmedabad",
        "Jaipur", "Surat", "Lucknow", "Kanpur", "Nagpur", "Indore", "Thane", "Bhopal", "Visakhapatnam",
        "Patna", "Vadodara", "Ghaziabad", "Ludhiana", "Agra", "Nashik", "Faridabad", "Meerut", "Rajkot",
        "Varanasi", "Srinagar", "Aurangabad", "Dhanbad", "Amritsar", "Navi Mumbai", "Allahabad", "Prayagraj",
        "Ranchi", "Howrah", "Coimbatore", "Jabalpur", "Gwalior", "Vijayawada", "Jodhpur", "Madurai",
        "Raipur", "Kota", "Guwahati", "Chandigarh", "Solapur", "Hubli", "Mysore", "Gurgaon", "Gurugram",
        "Noida", "Greater Noida", "Bhubaneswar", "Kochi", "Thiruvananthapuram", "Dehradun", "Shimla", "Jammu",
        "Mangalore", "Kozhikode", "Trichy", "Tirupati", "Pondicherry", "Puducherry", "Panaji", "Goa",
        "Shillong", "Imphal", "Agartala", "Aizawl", "Kohima", "Gangtok", "Itanagar", "Andheri", "Bandra",
        "Borivali", "Dadar", "Juhu", "Powai", "Kalyan", "Karol Bagh", "Chandni Chowk", "Lajpat Nagar",
        "Saket", "Hauz Khas", "Rohini", "Dwarka", "Koramangala", "Indiranagar", "Whitefield", "HSR Layout",
        "Jayanagar", "T Nagar", "Adyar", "Anna Nagar", "Velachery", "New Town", "Gariahat", "Ballygunge",
        "Hinjewadi", "Viman Nagar", "Kothrud", "Baner", "Banjara Hills", "Jubilee Hills", "Gachibowli",
        "Hitec City", "Rajiv Chowk", "India", "Bharat", "Hindustan",
    )

    private val ENTITY_REGEX = Regex(
        """(@\w+)|(https?://\S+)|(\b\d{2,}\b)|(\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b)|(\bSwarSetu\b)|(\biTantra\b)|(\bISRO\b)|(\bNDRF\b)|(\bSDRF\b)|(\bSOS\b)|(\bAIIMS\b)|(\bDRDO\b)|(\bIMD\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val NAME_INTRODUCTION_PATTERNS = listOf(
        Regex("""(?i)(?:मेरा\s+नाम|नाम\s+है|माझे\s+नाव|नाव\s+आहे|મારું\s+નામ|મારુ\s+નામ|મારા\s+ભાઈ|আমার\s+নাম|என்\s+பெயர்|నా\s+పేరు|ನನ್ನ\s+ಹೆಸರು|എന്റെ\s+പേര്|ମୋର\s+ନାମ|ମୋ\s+ନାମ)\s+([^\s,।!?]+(?:\s+[^\s,।!?]+)*)"""),
        Regex("""(?i)(?:मैं|मै|હું|मी|আমি|ഞാൻ|నేను|ನಾನು|ମୁଁ)\s+([^\s,।!?]+(?:\s+[^\s,।!?]+)*)\s+(?:हूँ|हु|हूं|છું|बोल रहा हूँ|बोलतोय)"""),
        Regex("""(?i)(?:my\s+name\s+is|this\s+is|call\s+me|speaking\s+with|speaking\s+to|meet\s+my\s+friend)\s+([a-zA-Z\u0900-\u0D7F]+(?:\s+[a-zA-Z\u0900-\u0D7F]+)*)"""),
        Regex("""(?i)\b(?:I\s+am|i'm|im)\s+([a-zA-Z\u0900-\u0D7F]+(?:\s+[a-zA-Z\u0900-\u0D7F]+)*)\b(?!\s+(?:going|coming|doing|here|there|not|at|in|on|with|from|a|an|the|very|so|too|feeling|trying|ready|fine|good|ok|okay|happy|sad|well))"""),
        Regex("""(?i)(?:Dr\.?|Mr\.?|Mrs\.?|Ms\.?|Prof\.?|Shri|Smt\.?|Sir|Madam|Pt\.?|Pandit|Swami|श्री|श्रीमती|डॉ\.?|डॉक्टर|प्रोफेसर|मिस्टर|मिस|पंडित|स्वामी)\s+([^\s,।!?]+(?:\s+[^\s,।!?]+)*)"""),
        Regex("""(?i)([^\s,।!?]+(?:\s+[^\s,।!?]+)*)\s+(?:जी|भाई|साहेब|साहब|सर|मैडम|दीदी|दादा|काका|अंकल|आंटी|ગારુ|அவர்கள்|ಅವರು)"""),
    )

    private fun cleanCandidateNoun(candidate: String): String? {
        val words = candidate.trim().split(Regex("""\s+"""))
        val validWords = mutableListOf<String>()
        for (w in words) {
            val lower = w.lowercase().trim('.', ',', '!', '?', '।', ':', ';')
            if (lower.isEmpty()) continue
            if (lower in COMMON_ENGLISH_WORDS || lower in COMMON_INDIC_WORDS) {
                break
            }
            validWords.add(w.trim('.', ',', '!', '?', '।', ':', ';'))
        }
        if (validWords.isEmpty()) return null
        val joined = validWords.joinToString(" ").trim()
        return if (joined.length >= 2) joined else null
    }

    private data class MaskedText(
        val text: String,
        val replacements: Map<String, String>,
        val tagToOriginal: Map<String, String>,
    )

    private fun maskEntities(rawText: String, customProtectedNouns: List<String> = emptyList()): MaskedText {
        val replacements = mutableMapOf<String, String>()
        val tagToOriginal = mutableMapOf<String, String>()
        var counter = 0
        var processed = rawText

        val allNouns = customProtectedNouns.filter { it.isNotBlank() }.toMutableList()

        for (known in KNOWN_CRITICAL_ENTITIES) {
            if (rawText.contains(known, ignoreCase = true) && !allNouns.any { it.equals(known, ignoreCase = true) }) {
                allNouns.add(known)
            }
        }

        for (pattern in NAME_INTRODUCTION_PATTERNS) {
            val matches = pattern.findAll(rawText)
            for (match in matches) {
                if (match.groupValues.size > 1) {
                    val candidate = match.groupValues[1]
                    val cleaned = cleanCandidateNoun(candidate)
                    if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                        allNouns.add(cleaned)
                    }
                }
            }
        }

        val commActionPattern = Regex("""(?i)\b(?:call|tell|ask|meet|contact|speaking\s+with|speaking\s+to|talk\s+to|with|for|dear|hello|hi|hey)\s+([a-zA-Z\u0900-\u0D7F]+(?:\s+[a-zA-Z\u0900-\u0D7F]+)*)""")
        for (match in commActionPattern.findAll(rawText)) {
            if (match.groupValues.size > 1) {
                val cleaned = cleanCandidateNoun(match.groupValues[1])
                if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                    allNouns.add(cleaned)
                }
            }
        }

        val prepLocationPattern = Regex("""(?i)\b(?:in|at|near|from|to|around|towards)\s+([a-zA-Z\u0900-\u0D7F]+(?:\s+[a-zA-Z\u0900-\u0D7F]+)*)""")
        for (match in prepLocationPattern.findAll(rawText)) {
            if (match.groupValues.size > 1) {
                val cleaned = cleanCandidateNoun(match.groupValues[1])
                if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                    allNouns.add(cleaned)
                }
            }
        }

        val multiWordCap = Regex("""\b[A-Z][a-zA-Z0-9_.-]*(?:\s+[A-Z0-9][a-zA-Z0-9_.-]*)+\b""").findAll(rawText)
        for (mMatch in multiWordCap) {
            val phrase = mMatch.value.trim()
            val cleaned = cleanCandidateNoun(phrase)
            if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                allNouns.add(cleaned)
            }
        }

        val singleCap = Regex("""\b[A-Z][a-zA-Z0-9_]{1,25}\b""").findAll(rawText)
        for (capMatch in singleCap) {
            val word = capMatch.value.trim()
            val cleaned = cleanCandidateNoun(word)
            if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                allNouns.add(cleaned)
            }
        }

        for (noun in allNouns.distinct().sortedByDescending { it.length }) {
            if (noun.isBlank() || noun.length < 2) continue
            val unicodeBoundaryRegex = Regex("""(?i)(?<=^|[\s\p{Punct}])${Regex.escape(noun)}(?=$|[\s\p{Punct}])""")
            if (unicodeBoundaryRegex.containsMatchIn(processed)) {
                val placeholder = "__N${counter}__"
                val match = unicodeBoundaryRegex.find(processed)
                if (match != null) {
                    replacements[placeholder] = match.value
                    tagToOriginal[counter.toString()] = match.value
                    processed = unicodeBoundaryRegex.replace(processed, placeholder)
                    counter++
                }
            } else if (processed.contains(noun, ignoreCase = true)) {
                val placeholder = "__N${counter}__"
                replacements[placeholder] = noun
                tagToOriginal[counter.toString()] = noun
                processed = processed.replace(noun, placeholder, ignoreCase = true)
                counter++
            }
        }

        processed = ENTITY_REGEX.replace(processed) { matchResult ->
            val placeholder = "__N${counter}__"
            replacements[placeholder] = matchResult.value
            tagToOriginal[counter.toString()] = matchResult.value
            counter++
            placeholder
        }

        return MaskedText(processed, replacements, tagToOriginal)
    }

    private fun unmaskEntities(translatedText: String, tagToOriginal: Map<String, String>): String {
        var result = translatedText

        val indicDigits = mapOf(
            "0" to listOf("०", "૦", "০", "௦", "౦", "೦", "൦", "୦", "शून्य", "zero", "o", "O"),
            "1" to listOf("१", "૧", "১", "௧", "౧", "೧", "൧", "୧", "एक", "one"),
            "2" to listOf("२", "૨", "২", "௨", "౨", "೨", "൨", "୨", "दो", "two"),
            "3" to listOf("३", "૩", "৩", "௩", "౩", "೩", "൩", "୩", "तीन", "three"),
            "4" to listOf("४", "૪", "৪", "௪", "౪", "೪", "൪", "୪", "चार", "four"),
            "5" to listOf("५", "૫", "৫", "௫", "౫", "೫", "൫", "୫", "पांच", "five"),
            "6" to listOf("६", "૬", "৬", "௬", "౬", "೬", "൬", "୬", "छह", "six"),
            "7" to listOf("७", "૭", "৭", "௭", "౭", "೭", "൭", "୭", "सात", "seven"),
            "8" to listOf("८", "૮", "৮", "௮", "౮", "೮", "൮", "୮", "आठ", "eight"),
            "9" to listOf("९", "૯", "৯", "௯", "౯", "೯", "൯", "୯", "नौ", "nine"),
        )

        for ((tagId, original) in tagToOriginal) {
            val variations = indicDigits[tagId] ?: emptyList()
            val digitPatternPart = (listOf(Regex.escape(tagId)) + variations.map { Regex.escape(it) }).joinToString("|")

            val openBracket = listOf("⟦", "[", "(", "{", "<").joinToString("|") { Regex.escape(it) }
            val closeBracket = listOf("⟧", "]", ")", "}", ">").joinToString("|") { Regex.escape(it) }

            val indicNAndP = listOf(
                "एन", "पि", "पी", "એન", "પી", "પિ", "এন", "পি", "পী", "என்", "பி", "பீ",
                "ఎన్", "పి", "పీ", "ಎನ್", "ಪಿ", "ಪೀ", "എൻ", "പി", "പീ", "ଏନ", "ପି", "ପୀ",
                "N", "n", "P", "p", "ID", "id", "NO", "no", "NOUN", "noun", "ENTITY", "entity"
            )
            val prefix = indicNAndP.joinToString("|") { Regex.escape(it) }

            val lenientRegex = Regex(
                """(?:_{1,3}\s*(?:$prefix)?\s*[-_–]?\s*(?:$digitPatternPart)\s*_{1,3})|""" +
                    """(?:(?:$openBracket)\s*(?:$prefix)?\s*[-_–]?\s*(?:$digitPatternPart)\s*(?:$closeBracket))|""" +
                    """(?:\b(?:$prefix)[-_–\s]?(?:$digitPatternPart)\b)|""" +
                    """(?:(?:$prefix)\s*[-_–]?\s*(?:$digitPatternPart))""",
                RegexOption.IGNORE_CASE,
            )

            result = if (lenientRegex.containsMatchIn(result)) {
                lenientRegex.replace(result, original)
            } else {
                result.replace("__N${tagId}__", original, ignoreCase = true)
                    .replace("⟦P${tagId}⟧", original, ignoreCase = true)
                    .replace("[P${tagId}]", original, ignoreCase = true)
                    .replace("[N${tagId}]", original, ignoreCase = true)
                    .replace("N${tagId}", original, ignoreCase = true)
            }
        }

        for ((_, original) in tagToOriginal) {
            if (!result.contains(original, ignoreCase = true) && original.length >= 2) {
                val candidatePlaceholderRegex = Regex("""__N\d+__|\bN\d+\b|⟦P\d+⟧|\[P\d+\]|\[N\d+\]""")
                if (candidatePlaceholderRegex.containsMatchIn(result)) {
                    result = candidatePlaceholderRegex.replaceFirst(result, original)
                }
            }
        }

        return result
    }

    private fun preprocessText(input: String, sourceLang: String = ""): String {
        var trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed[0] in 'a'..'z') {
            trimmed = trimmed.replaceFirstChar { it.uppercase() }
        }
        val lastChar = trimmed.last()
        if (lastChar !in listOf('.', '?', '!', '।', ',', ';', ':')) {
            trimmed = if (sourceLang == "en" || sourceLang == "english") {
                "$trimmed."
            } else {
                "$trimmed ।"
            }
        }
        return trimmed
    }

    private fun postprocessText(input: String): String {
        return input
            .replace(Regex("""\s+([.,!?।:;])"""), "$1")
            .replace(Regex("""([.,!?।:;])(?=[^\s.,!?।:;])"""), "$1 ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    /**
     * Infers language code from Unicode script characteristics of the input text.
     */
    fun detectLanguageFromScript(text: String): String? {
        val detected = IndicLanguage.detectFromScript(text)
        return detected?.code
    }

    private suspend fun translatePassage(
        passage: String,
        source: IndicLanguage,
        target: IndicLanguage,
    ): String {
        val sentences = passage.split(Regex("""(?<=[।?!.\n;])\s+""")).filter { it.isNotBlank() }
        if (sentences.size <= 1) {
            return indicTransEngine.translate(passage, source, target).translatedText
        }
        val translatedSentences = sentences.map { sentence ->
            indicTransEngine.translate(sentence.trim(), source, target).translatedText
        }
        return translatedSentences.joinToString(" ")
    }

    /**
     * Translates text from [sourceLang] to [targetLang].
     * Supports noun/entity preservation across Indian language pairs and English.
     */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String = translate(text, sourceLang, targetLang, emptyList())

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        protectedNouns: List<String> = emptyList(),
    ): String {
        if (text.isBlank()) return text
        if (sourceLang == targetLang) return text

        val sourceIndic = IndicLanguage.fromCode(sourceLang)
            ?: IndicLanguage.detectFromScript(text)
            ?: IndicLanguage.ENGLISH
        val targetIndic = IndicLanguage.fromCode(targetLang) ?: return text

        if (sourceIndic == targetIndic) return text

        return try {
            // 1. Mask proper nouns, multi-word entities, mentions, URLs, numbers
            val (masked, _, tagToOriginal) = maskEntities(text, protectedNouns)

            // 2. Preprocess sentence structure
            val preprocessed = preprocessText(masked, sourceIndic.code)

            // 3. Execute neural translation via IndicTrans2
            val translated = translatePassage(preprocessed, sourceIndic, targetIndic)

            // 4. Restore preserved entities and clean up syntax
            val unmasked = unmaskEntities(translated, tagToOriginal)
            postprocessText(unmasked)
        } catch (e: Exception) {
            val cause = e.message?.lowercase() ?: ""
            if (cause.contains("model") || cause.contains("download")) {
                Log.w(
                    "TranslatorEngine",
                    "Translation failed: IndicTrans2 model for " +
                        "$sourceLang (${sourceIndic.code})→$targetLang (${targetIndic.code}) is not downloaded. Falling back to original text.",
                    e,
                )
            } else {
                Log.w("TranslatorEngine", "Translation failed for $sourceLang (${sourceIndic.code})→$targetLang (${targetIndic.code}): ${e.message}", e)
            }
            text
        } finally {
            // Translation cycle finished
        }
    }

    /**
     * Downloads IndicTrans2 model packages for offline readiness.
     */
    suspend fun downloadAllRequiredModels(onProgress: (Int, Int, String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val bundles = listOf(
                IndicTransModelBundle.INDIC_INDIC,
                IndicTransModelBundle.EN_INDIC,
                IndicTransModelBundle.INDIC_EN,
            )
            var completedCount = 0
            val total = bundles.size

            for (bundle in bundles) {
                if (modelManager.isModelDownloaded(bundle)) {
                    completedCount++
                    onProgress(completedCount, total, bundle.displayName)
                } else {
                    val success = modelManager.downloadModel(bundle) { _, _, pct, file ->
                        onProgress(completedCount, total, "${bundle.displayName} ($pct% - $file)")
                    }
                    if (success) {
                        completedCount++
                        onProgress(completedCount, total, bundle.displayName)
                    }
                }
            }
            completedCount > 0
        }
}

