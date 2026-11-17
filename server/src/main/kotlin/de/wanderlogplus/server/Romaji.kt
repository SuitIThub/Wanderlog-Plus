package de.wanderlogplus.server

import com.atilika.kuromoji.ipadic.Tokenizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Japanese station names in Hepburn romaji: the reading from the Kuromoji dictionary (IPADIC), long vowels with a
 * macron ("Kyōto"), word parts joined with a hyphen ("Shin-Ōsaka"). Names with a special reading come from
 * [SPECIAL]. A part without a known reading (unknown kanji) makes the whole name unknown: "" rather than a guess.
 */
object Romaji {
    /** Proper names the dictionary reads wrongly or not at all (whole names or parts). */
    private val SPECIAL = mapOf(
        "南木曽" to "Nagiso", "妻籠" to "Tsumago", "馬籠" to "Magome", "奈良井" to "Narai", "上松" to "Agematsu",
        "木曽福島" to "Kiso-Fukushima", "薮原" to "Yabuhara", "藪原" to "Yabuhara", "宮ノ越" to "Miyanokoshi",
        "落合川" to "Ochiaigawa", "坂下" to "Sakashita", "十二兼" to "Jūnikane", "野尻" to "Nojiri",
        "大桑" to "Ōkuwa", "須原" to "Suhara", "倉本" to "Kuramoto", "原野" to "Harano", "日義" to "Hiyoshi",
        "嵐山" to "Arashiyama", "祇園四条" to "Gion-Shijō", "河原町" to "Kawaramachi", "伏見稲荷" to "Fushimi-Inari",
        "出町柳" to "Demachiyanagi", "近鉄奈良" to "Kintetsu-Nara", "高山" to "Takayama", "下呂" to "Gero",
        "清水五条" to "Kiyomizu-Gojō", "清水寺" to "Kiyomizu-dera", "京阪" to "Keihan", "難波" to "Namba",
    )

    /** Generic endings split off a longer dictionary word (short ones like 道 or 前 only as words of their own). */
    private val SPLIT_TAILS = listOf("駅前", "駅", "空港", "ターミナル", "バスターミナル")

    /** Generic words of station names, each its own part ("Kyōto-Ekimae", "Narita-Kūkō", "Ginkakuji-mae"). */
    private val GENERIC = mapOf(
        "駅前" to "Ekimae", "駅" to "Eki", "空港" to "Kūkō", "ターミナル" to "Terminal", "バスターミナル" to "Busterminal",
        "前" to "mae", "道" to "michi", "口" to "guchi", "第" to "Dai", "線" to "Linie", "港" to "Kō",
    )

    private val tokenizer by lazy { Tokenizer() }
    private val cache = ConcurrentHashMap<String, String>()

    /** The romaji of the Japanese name [ja]; "" when it has no Japanese or cannot be read. */
    fun of(ja: String): String {
        val name = ja.trim()
        if (name.isEmpty() || name.none { isJapanese(it) }) return ""
        return cache.getOrPut(name) { read(name) }
    }

    private fun isJapanese(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    private fun read(name: String): String {
        SPECIAL[name]?.let { return it }
        // Known parts first ("南木曽駅" → Nagiso + Eki), the rest by the dictionary.
        SPECIAL.entries.sortedByDescending { it.key.length }.firstOrNull { name.contains(it.key) }?.let { (k, v) ->
            val parts = name.split(k).map { it.trim() }
            val rest = parts.map { if (it.isEmpty()) "" else read(it) }
            if (rest.any { it.isEmpty() && false }) return ""
            return parts.indices.flatMap { i -> listOfNotNull(rest[i].takeIf { it.isNotEmpty() }, v.takeIf { i < parts.lastIndex }) }.joinToString("-")
        }
        // "新…" (Shin-Ōsaka, Shin-Yokohama): the prefix is its own part even when the dictionary knows the whole name.
        if (name.length >= 3 && name.startsWith("新")) read(name.substring(1)).takeIf { it.isNotEmpty() }?.let { return "Shin-$it" }
        // Parts: a new hyphenated part after a prefix and between two proper names; suffixes (…川, …駅前) stay attached.
        val parts = mutableListOf<StringBuilder>()
        var prevProper = false
        var prevPrefix = false
        for (t in tokenizer.tokenize(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC))) {
            val surface = t.surface
            if (surface.isBlank() || surface.all { it in "・･-－ー（）()" }) { prevProper = false; prevPrefix = true; continue }
            // A dictionary word that ends in a generic one ("成田空港"): read the front, then the generic part.
            val tail = SPLIT_TAILS.filter { surface.length > it.length && surface.endsWith(it) }.maxByOrNull { it.length }
                // "…前" in front of a landmark (銀閣寺前, 二条城前): "Ginkakuji-mae".
                ?: "前".takeIf { surface.length > 2 && surface.endsWith(it) && surface[surface.length - 2] in "寺城宮園社橋院館" }
            if (tail != null) {
                val front = read(surface.dropLast(tail.length)).takeIf { it.isNotEmpty() } ?: return ""
                parts += StringBuilder(front)
                parts += StringBuilder(GENERIC.getValue(tail))
                prevProper = false; prevPrefix = true; continue
            }
            val fixed = GENERIC[surface] ?: SPECIAL[surface]
            if (fixed != null) {
                // 駅 + 前 is one word: "Ekimae".
                if (fixed == "mae" && parts.lastOrNull()?.toString() == "Eki") parts.last().append("mae") else parts += StringBuilder(fixed)
                prevProper = false; prevPrefix = true; continue
            }
            if (surface.all { it.code < 128 }) { parts += StringBuilder(surface); prevProper = false; prevPrefix = true; continue }
            val word = if (surface.all { it.code < 128 }) surface else {
                val reading = t.reading?.takeIf { it != "*" && it.isNotBlank() }
                    ?: surface.takeIf { s -> s.all { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.KATAKANA || Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HIRAGANA } }
                    ?: return ""
                kana(reading)
            }
            val proper = t.partOfSpeechLevel2 == "固有名詞"
            val suffix = t.partOfSpeechLevel2 == "接尾"
            val newPart = parts.isEmpty() || prevPrefix || (proper && prevProper && !suffix)
            if (newPart) parts += StringBuilder(word) else parts.last().append(word.lowercase())
            prevProper = proper
            prevPrefix = t.partOfSpeechLevel1 == "接頭詞"
        }
        // Lowercase generic parts (mae, michi, guchi) keep their case; every other part starts with a capital.
        return parts.joinToString("-") { p -> p.toString().let { w -> if (w in GENERIC.values && w.first().isLowerCase()) w else w.replaceFirstChar { c -> c.uppercaseChar() } } }
    }

    private val PAIRS = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo", "シャ" to "sha", "シュ" to "shu", "ショ" to "sho", "シェ" to "she",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "チェ" to "che", "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo", "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo", "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ジェ" to "je", "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo", "ティ" to "ti", "ディ" to "di", "ファ" to "fa", "フィ" to "fi",
        "フェ" to "fe", "フォ" to "fo", "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo", "ヴァ" to "va", "ヴィ" to "vi",
    )
    private val SINGLE = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o", 'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so", 'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no", 'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo", 'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro", 'ワ' to "wa", 'ヲ' to "o", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go", 'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do", 'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po", 'ヴ' to "vu",
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o", 'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo",
    )
    private val MACRON = mapOf('a' to 'ā', 'i' to 'ī', 'u' to 'ū', 'e' to 'ē', 'o' to 'ō')

    /** Katakana (or hiragana) in Hepburn, capitalised: "キョウト" → "Kyōto", "ハッチョウボリ" → "Hatchōbori". */
    fun kana(text: String): String {
        val k = text.map { c -> if (c in 'ぁ'..'ゖ') c + 0x60 else c }.joinToString("")
        val syl = mutableListOf<String>()
        var i = 0
        var double = false
        while (i < k.length) {
            val c = k[i]
            val two = if (i + 1 < k.length) k.substring(i, i + 2) else ""
            when {
                c == 'ッ' -> { double = true; i++; continue }
                c == 'ー' -> {
                    if (syl.isNotEmpty()) syl[syl.lastIndex] = longVowel(syl.last())
                    i++; continue
                }
                PAIRS.containsKey(two) -> { syl += PAIRS.getValue(two); i += 2 }
                SINGLE.containsKey(c) -> { syl += SINGLE.getValue(c); i++ }
                else -> { syl += c.toString(); i++ }
            }
            if (double) {
                val s = syl.last()
                syl[syl.lastIndex] = (if (s.startsWith("ch")) "t" else s.take(1)) + s
                double = false
            }
        }
        // Long vowels: ou, oo, uu → ō, ū (not across "n").
        val out = StringBuilder()
        syl.forEachIndexed { j, s ->
            val prev = out.lastOrNull()
            when {
                (s == "u" && prev == 'o') || (s == "o" && prev == 'o') -> out.setCharAt(out.length - 1, 'ō')
                s == "u" && prev == 'u' -> out.setCharAt(out.length - 1, 'ū')
                // "n" before a vowel or y: n' (Shin'yokohama).
                j > 0 && syl[j - 1] == "n" && (s.first() in "aiueo" || s.first() == 'y') -> out.append('\'').append(s)
                else -> out.append(s)
            }
        }
        return out.toString().replaceFirstChar { it.uppercaseChar() }
    }

    private fun longVowel(s: String): String = MACRON[s.last()]?.let { s.dropLast(1) + it } ?: s
}

/** The station with a romaji name when it has none yet (names from the timetable or OpenStreetMap stay). */
fun de.wanderlogplus.shared.sync.TransitStation.withRomaji(): de.wanderlogplus.shared.sync.TransitStation =
    if (name.isNotBlank()) this else copy(name = Romaji.of(nameJa))

/** Every station and stop of the result with a romaji name where it had none. */
fun de.wanderlogplus.shared.sync.TransitResult.withRomaji(): de.wanderlogplus.shared.sync.TransitResult = copy(
    fromStation = fromStation.withRomaji(),
    toStation = toStation.withRomaji(),
    routes = routes.map { r ->
        r.copy(legs = r.legs.map { l ->
            l.copy(
                from = l.from.withRomaji(),
                to = l.to.withRomaji(),
                stops = l.stops.map { st -> if (st.name.isNotBlank()) st else st.copy(name = Romaji.of(st.nameJa)) },
            )
        })
    },
)
