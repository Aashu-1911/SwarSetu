package app.swarsetu.translation

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitTask(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { exception -> cont.resumeWithException(exception) }
        addOnCanceledListener { cont.cancel() }
    }

/**
 * Handles on-device machine translation using Google ML Kit with entity shielding,
 * complex sentence clause routing, and resilient Indic-to-Indic English pivoting.
 */
class TranslatorEngine {
    private val translatorCache = ConcurrentHashMap<String, Translator>()
    private val downloadedModels = ConcurrentHashMap<String, Boolean>()

    /**
     * Normalizes language codes/names ("english", "en", "hindi", "hi", "mr", "gu", "ta", "te", "kn", "bn", "ml", "or") to standard ML Kit tags.
     */
    fun normalizeToLanguageTag(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        val clean = lang.trim().lowercase().split("-", "_").first()
        return when (clean) {
            "en", "english" -> TranslateLanguage.ENGLISH
            "hi", "hindi" -> TranslateLanguage.HINDI
            "gu", "gujarati" -> TranslateLanguage.GUJARATI
            "mr", "marathi" -> TranslateLanguage.MARATHI
            "kn", "kannada" -> TranslateLanguage.KANNADA
            "ta", "tamil" -> TranslateLanguage.TAMIL
            "te", "telugu" -> TranslateLanguage.TELUGU
            "bn", "bengali" -> TranslateLanguage.BENGALI
            "ml", "malayalam" -> TranslateLanguage.HINDI // ML Kit on-device translation alias
            "or", "odia", "oriya" -> TranslateLanguage.HINDI // ML Kit on-device translation alias
            else -> TranslateLanguage.fromLanguageTag(clean) ?: TranslateLanguage.ENGLISH
        }
    }

    private fun getOrCreateTranslator(
        source: String,
        target: String,
    ): Translator {
        val key = "$source->$target"
        return translatorCache.computeIfAbsent(key) {
            val options =
                TranslatorOptions
                    .Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            Translation.getClient(options)
        }
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
        "ആണ്", "ഉണ്ട്", "ആയിരുന്നു", "ഒപ്പം", "നിന്ന്", "ഇൽ", "ഞാൻ", "നിങ്ങൾ", "എന്റെ", "നിങ്ങളുടെ"
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
                break // Stop as soon as a grammar stop word / particle is reached
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

        // 1. Add known emergency, city, and landmark entities if present in raw text
        for (known in KNOWN_CRITICAL_ENTITIES) {
            if (rawText.contains(known, ignoreCase = true) && !allNouns.any { it.equals(known, ignoreCase = true) }) {
                allNouns.add(known)
            }
        }

        // 2. Extract names from conversational introduction patterns
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

        // 3. Extract entities following action/communication verbs (e.g. "call Rahul", "meet Priya", "hello Ashish")
        val commActionPattern = Regex("""(?i)\b(?:call|tell|ask|meet|contact|speaking\s+with|speaking\s+to|talk\s+to|with|for|dear|hello|hi|hey)\s+([a-zA-Z\u0900-\u0D7F]+(?:\s+[a-zA-Z\u0900-\u0D7F]+)*)""")
        for (match in commActionPattern.findAll(rawText)) {
            if (match.groupValues.size > 1) {
                val cleaned = cleanCandidateNoun(match.groupValues[1])
                if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                    allNouns.add(cleaned)
                }
            }
        }

        // 4. Extract location candidates following prepositions (e.g. "in Pune", "at AIIMS", "near Rajiv Chowk")
        val prepLocationPattern = Regex("""(?i)\b(?:in|at|near|from|to|around|towards)\s+([a-zA-Z\u0900-\u0D7F]+(?:\s+[a-zA-Z\u0900-\u0D7F]+)*)""")
        for (match in prepLocationPattern.findAll(rawText)) {
            if (match.groupValues.size > 1) {
                val cleaned = cleanCandidateNoun(match.groupValues[1])
                if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                    allNouns.add(cleaned)
                }
            }
        }

        // 5. Extract multi-word capitalized proper noun phrases in English/Latin (e.g. "Ashish Kumar", "New Delhi", "Sector 18")
        val multiWordCap = Regex("""\b[A-Z][a-zA-Z0-9_.-]*(?:\s+[A-Z0-9][a-zA-Z0-9_.-]*)+\b""").findAll(rawText)
        for (mMatch in multiWordCap) {
            val phrase = mMatch.value.trim()
            val cleaned = cleanCandidateNoun(phrase)
            if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                allNouns.add(cleaned)
            }
        }

        // 6. Extract single capitalized proper nouns in English/Latin (e.g. Ashish, Mumbai, Pune)
        val singleCap = Regex("""\b[A-Z][a-zA-Z0-9_]{1,25}\b""").findAll(rawText)
        for (capMatch in singleCap) {
            val word = capMatch.value.trim()
            val cleaned = cleanCandidateNoun(word)
            if (cleaned != null && !allNouns.any { it.equals(cleaned, ignoreCase = true) }) {
                allNouns.add(cleaned)
            }
        }

        // 7. Shield custom and dynamically extracted proper nouns (longest matches first to preserve multi-word entities)
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

        // 8. Shield URLs, emails, mentions, numbers, and technical acronyms
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

        // Map of digit to Indic numerals and phonetic script variations
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

        // Failsafe: If any original entity was omitted or unmasked incorrectly, restore it
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
            trimmed = if (sourceLang == TranslateLanguage.ENGLISH || sourceLang == "en") {
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
     * Infers ML Kit language code from Unicode script characteristics of the input text.
     */
    fun detectLanguageFromScript(text: String): String? {
        for (char in text) {
            when (char.code) {
                in 0x0A80..0x0AFF -> return TranslateLanguage.GUJARATI
                in 0x0980..0x09FF -> return TranslateLanguage.BENGALI
                in 0x0B80..0x0BFF -> return TranslateLanguage.TAMIL
                in 0x0C00..0x0C7F -> return TranslateLanguage.TELUGU
                in 0x0C80..0x0CFF -> return TranslateLanguage.KANNADA
                in 0x0D00..0x0D7F -> return TranslateLanguage.HINDI // Malayalam script -> mapped to Indic neural engine
                in 0x0B00..0x0B7F -> return TranslateLanguage.HINDI // Odia script -> mapped to Indic neural engine
                in 0x0900..0x097F -> return TranslateLanguage.HINDI // Devanagari script (Hindi / Marathi)
            }
        }
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' } && text.none { it.code in 0x0900..0x0D7F }) {
            return TranslateLanguage.ENGLISH
        }
        return null
    }

    private suspend fun translateDirect(
        text: String,
        source: String,
        target: String,
    ): String {
        val translator = getOrCreateTranslator(source, target)
        val conditions = DownloadConditions.Builder().build()
        val modelManager = RemoteModelManager.getInstance()

        if (downloadedModels[source] != true) {
            val sourceModel = TranslateRemoteModel.Builder(source).build()
            if (modelManager.isModelDownloaded(sourceModel).awaitTask() != true) {
                modelManager.download(sourceModel, conditions).awaitTask()
            }
            downloadedModels[source] = true
        }

        if (downloadedModels[target] != true) {
            val targetModel = TranslateRemoteModel.Builder(target).build()
            if (modelManager.isModelDownloaded(targetModel).awaitTask() != true) {
                modelManager.download(targetModel, conditions).awaitTask()
            }
            downloadedModels[target] = true
        }

        translator.downloadModelIfNeeded(conditions).awaitTask()
        return translator.translate(text).awaitTask() ?: text
    }

    private suspend fun translateDirectOrPivot(
        text: String,
        source: String,
        target: String,
    ): String {
        return if (source == TranslateLanguage.ENGLISH || target == TranslateLanguage.ENGLISH) {
            translateDirect(text, source, target)
        } else {
            // Pivot via English with intermediate validation
            val englishIntermediate = translateDirect(text, source, TranslateLanguage.ENGLISH)
            if (englishIntermediate.isNotBlank() && englishIntermediate != text) {
                translateDirect(englishIntermediate, TranslateLanguage.ENGLISH, target)
            } else {
                translateDirect(text, source, target)
            }
        }
    }

    private suspend fun translatePassage(
        passage: String,
        source: String,
        target: String,
    ): String {
        // Segment by sentence boundaries while preserving paragraph flow
        val sentences = passage.split(Regex("""(?<=[।?!.\n;])\s+""")).filter { it.isNotBlank() }
        if (sentences.size <= 1) {
            return translateDirectOrPivot(passage, source, target)
        }
        val translatedSentences = sentences.map { sentence ->
            translateDirectOrPivot(sentence.trim(), source, target)
        }
        return translatedSentences.joinToString(" ")
    }

    /**
     * Translates text from [sourceLang] to [targetLang].
     * Supports noun/entity preservation and English pivot translation between Indian language pairs.
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

        var detectedSource = normalizeToLanguageTag(sourceLang)
        val target = normalizeToLanguageTag(targetLang) ?: return text

        // Reconcile source language against actual Unicode script to prevent cross-language distortion
        val scriptDetected = detectLanguageFromScript(text)
        if (scriptDetected != null && (detectedSource == null || (detectedSource == TranslateLanguage.ENGLISH && scriptDetected != TranslateLanguage.ENGLISH))) {
            detectedSource = scriptDetected
        }
        val source = detectedSource ?: scriptDetected ?: TranslateLanguage.ENGLISH

        if (source == target) return text

        return try {
            // 1. Mask proper nouns, multi-word entities, mentions, URLs, numbers
            val (masked, _, tagToOriginal) = maskEntities(text, protectedNouns)

            // 2. Preprocess sentence structure
            val preprocessed = preprocessText(masked, source)

            // 3. Execute translation (Direct for English pairs; Pivot via English for Indic-to-Indic pairs)
            val translated = translatePassage(preprocessed, source, target)

            // 4. Restore preserved entities and clean up syntax
            val unmasked = unmaskEntities(translated, tagToOriginal)
            postprocessText(unmasked)
        } catch (e: Exception) {
            val cause = e.message?.lowercase() ?: ""
            if (cause.contains("model") || cause.contains("download")) {
                Log.w(
                    "TranslatorEngine",
                    "Translation failed: ML Kit model for " +
                        "$sourceLang ($source)→$targetLang ($target) is not downloaded. Falling back to original text.",
                    e,
                )
            } else {
                Log.w("TranslatorEngine", "Translation failed for $sourceLang ($source)→$targetLang ($target): ${e.message}", e)
            }
            text
        } finally {
            // Translation cycle finished
        }
    }

    /**
     * Downloads all supported Indian language models in parallel batches for high-speed offline readiness.
     */
    suspend fun downloadAllRequiredModels(onProgress: (Int, Int, String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val supportedLanguages =
                    listOf(
                        TranslateLanguage.ENGLISH,
                        TranslateLanguage.HINDI,
                        TranslateLanguage.MARATHI,
                        TranslateLanguage.GUJARATI,
                        TranslateLanguage.TAMIL,
                        TranslateLanguage.TELUGU,
                        TranslateLanguage.BENGALI,
                        TranslateLanguage.KANNADA,
                    )

                val modelManager = RemoteModelManager.getInstance()
                val conditions = DownloadConditions.Builder().build()
                val total = supportedLanguages.size
                val completed = java.util.concurrent.atomic.AtomicInteger(0)
                val semaphore = Semaphore(3)

                val jobs =
                    supportedLanguages.map { lang ->
                        async {
                            semaphore.withPermit {
                                val model = TranslateRemoteModel.Builder(lang).build()
                                var downloaded = false
                                try {
                                    if (modelManager.isModelDownloaded(model).awaitTask() == true) {
                                        downloaded = true
                                    }
                                } catch (_: Exception) {}

                                if (!downloaded) {
                                    var attempts = 0
                                    while (!downloaded && attempts < 2) {
                                        attempts++
                                        try {
                                            modelManager.download(model, conditions).awaitTask()
                                            downloaded = true
                                            downloadedModels[lang] = true
                                            Log.d("TranslatorEngine", "Downloaded ML Kit model for $lang")
                                        } catch (e: Exception) {
                                            Log.w("TranslatorEngine", "Attempt $attempts failed for $lang: ${e.message}")
                                            if (attempts < 2) delay(300)
                                        }
                                    }
                                } else {
                                    downloadedModels[lang] = true
                                }

                                val count = completed.incrementAndGet()
                                onProgress(count, total, lang)
                                downloaded
                            }
                        }
                    }

                val results = jobs.map { it.await() }
                val englishDownloaded = downloadedModels[TranslateLanguage.ENGLISH] == true
                val hindiDownloaded = downloadedModels[TranslateLanguage.HINDI] == true

                englishDownloaded || hindiDownloaded || results.any { it }
            }
        }
}
