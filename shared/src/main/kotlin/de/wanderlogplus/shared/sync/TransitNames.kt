package de.wanderlogplus.shared.sync

/**
 * Readable versions of Japanese line and train names from the timetable search: "ＪＲ新幹線のぞみ15号" →
 * "JR Shinkansen Nozomi 15", "ＪＲ特急しなの7号" → "JR Ltd. Exp. Shinano 7", "ＪＲ中央本線区間快速" →
 * "JR Chūō-Hauptlinie (Section Rapid)". Unknown parts stay Japanese.
 */
object TransitNames {
    /** Named trains (Shinkansen, limited express) and other frequent names. */
    private val TRAINS = linkedMapOf(
        "のぞみ" to "Nozomi", "ひかり" to "Hikari", "こだま" to "Kodama", "みずほ" to "Mizuho", "さくら" to "Sakura",
        "つばめ" to "Tsubame", "はやぶさ" to "Hayabusa", "はやて" to "Hayate", "やまびこ" to "Yamabiko", "なすの" to "Nasuno",
        "こまち" to "Komachi", "つばさ" to "Tsubasa", "とき" to "Toki", "たにがわ" to "Tanigawa", "かがやき" to "Kagayaki",
        "はくたか" to "Hakutaka", "あさま" to "Asama", "つるぎ" to "Tsurugi", "かもめ" to "Kamome",
        "しなの" to "Shinano", "ひだ" to "Hida", "あずさ" to "Azusa", "かいじ" to "Kaiji", "はるか" to "Haruka",
        "サンダーバード" to "Thunderbird", "しらさぎ" to "Shirasagi", "くろしお" to "Kuroshio", "南紀" to "Nanki",
        "ワイドビューひだ" to "Wide View Hida", "ワイドビューしなの" to "Wide View Shinano", "成田エクスプレス" to "Narita Express",
        "スカイライナー" to "Skyliner", "踊り子" to "Odoriko", "サフィール踊り子" to "Saphir Odoriko", "ロマンスカー" to "Romancecar",
        "富士回遊" to "Fuji Excursion", "しおさい" to "Shiosai", "ひたち" to "Hitachi", "ときわ" to "Tokiwa",
        "やくも" to "Yakumo", "スーパーはくと" to "Super Hakuto", "はまかぜ" to "Hamakaze", "いなほ" to "Inaho",
    )

    /** Train types and general words, longest first so 区間快速 wins over 快速. */
    private val WORDS = listOf(
        "新幹線" to "Shinkansen", "特急" to "Ltd. Exp.", "急行" to "Express", "特別快速" to "Special Rapid",
        "新快速" to "Special Rapid", "区間快速" to "Section Rapid", "通勤快速" to "Commuter Rapid", "快速" to "Rapid",
        "準急" to "Semi Exp.", "各駅停車" to "Local", "普通" to "Local", "徒歩" to "zu Fuß", "地下鉄" to "U-Bahn",
        "高速バス" to "Fernbus", "路線バス" to "Bus", "バス" to "Bus", "ライナー" to "Liner", "本線" to "Hauptlinie",
    ).sortedByDescending { it.first.length }

    /** Lines that are good to recognise. */
    private val LINES = listOf(
        "東海道本線" to "Tōkaidō-Hauptlinie", "中央本線" to "Chūō-Hauptlinie", "中央線" to "Chūō-Linie", "高山本線" to "Takayama-Hauptlinie",
        "山手線" to "Yamanote-Linie", "京浜東北線" to "Keihin-Tōhoku-Linie", "総武線" to "Sōbu-Linie", "奈良線" to "Nara-Linie",
        "嵯峨野線" to "Sagano-Linie", "山陰本線" to "San'in-Hauptlinie", "琵琶湖線" to "Biwako-Linie", "湖西線" to "Kosei-Linie",
        "大阪環状線" to "Ōsaka-Loop-Linie", "京都線" to "Kyōto-Linie", "身延線" to "Minobu-Linie", "飯田線" to "Iida-Linie",
        "篠ノ井線" to "Shinonoi-Linie", "大糸線" to "Ōito-Linie", "名鉄" to "Meitetsu", "近鉄" to "Kintetsu", "京阪" to "Keihan",
        "阪急" to "Hankyū", "阪神" to "Hanshin", "南海" to "Nankai", "京急" to "Keikyū", "東急" to "Tōkyū", "小田急" to "Odakyū",
        "京王" to "Keiō", "西武" to "Seibu", "東武" to "Tōbu", "京成" to "Keisei", "都営" to "Toei", "東京メトロ" to "Tokyo Metro",
        "京都市営地下鉄" to "Kyōto U-Bahn", "京都市営" to "Kyōto U-Bahn", "名古屋市営" to "Nagoya U-Bahn", "大阪メトロ" to "Ōsaka Metro",
        "みやこ路" to "Miyakoji", "ＪＲ" to "JR", "烏丸線" to "Karasuma-Linie", "東西線" to "Tōzai-Linie", "名古屋市営地下鉄" to "Nagoya U-Bahn",
        "ゆいレール" to "Yui Rail", "嵐電" to "Randen", "叡山電鉄" to "Eizan-Bahn",
    ).sortedByDescending { it.first.length }

    /** Full-width Latin letters and digits → ASCII (ＪＲ → JR, １５ → 15). */
    fun ascii(s: String): String = buildString {
        for (c in s) append(if (c in '！'..'～') (c - 0xFEE0) else if (c == '　') ' ' else c)
    }

    /** Readable line / train name; parts without a translation are kept. */
    fun line(lineJa: String): String {
        var s = ascii(lineJa.trim())
        if (s == "徒歩") return "zu Fuß"
        // "のぞみ15号" → "Nozomi 15"
        for ((ja, en) in TRAINS.entries.sortedByDescending { it.key.length }) {
            s = s.replace(Regex(Regex.escape(ja) + "(\\d+)号")) { "$en ${it.groupValues[1]} " }.replace(ja, "$en ")
        }
        s = s.replace(Regex("(\\d+)号")) { "${it.groupValues[1]} " }
        for ((ja, en) in LINES) s = s.replace(ja, " $en ")
        for ((ja, en) in WORDS) s = s.replace(ja, " $en ")
        s = s.replace("JR", "JR ").replace(Regex("\\s+"), " ").trim()
        // A train type after the line reads better in brackets: "JR Chūō-Hauptlinie Section Rapid" → "… (Section Rapid)".
        val type = WORDS.map { it.second }.filter { it != "Shinkansen" && it != "Ltd. Exp." }.firstOrNull { s.endsWith(" $it") && s != it }
        return if (type != null && LINES.any { s.contains(it.second) }) s.removeSuffix(" $type") + " ($type)" else s
    }

    /** "博多行" → "Richtung Hakata"-style headsign; the station part stays as given (Latin when [station] is known). */
    fun headsign(headsignJa: String, station: String? = null): String {
        val name = station?.takeIf { it.isNotBlank() } ?: headsignJa.removeSuffix("行").removeSuffix("ゆき").trim()
        return if (name.isBlank()) "" else "Richtung $name"
    }

    /** "自由席" → "freier Sitz" and other fee labels. */
    fun fee(labelJa: String): String = when (labelJa.trim()) {
        "自由席" -> "Zuschlag, freier Sitz"
        "指定席" -> "Zuschlag, reservierter Sitz"
        "グリーン席", "グリーン車" -> "Green Car"
        "特急券", "特急料金" -> "Expresszuschlag"
        else -> labelJa
    }
}
