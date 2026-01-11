package com.brain.tracscript.plugins.gps

object RussianTransliterator {

    private val map = mapOf(
        // строчные
        'а' to "a",  'б' to "b",  'в' to "v",  'г' to "g",  'д' to "d",
        'е' to "e",  'ё' to "e",  'ж' to "zh", 'з' to "z",  'и' to "i",
        'й' to "y",  'к' to "k",  'л' to "l",  'м' to "m",  'н' to "n",
        'о' to "o",  'п' to "p",  'р' to "r",  'с' to "s",  'т' to "t",
        'у' to "u",  'ф' to "f",  'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y",  'ь' to "",
        'э' to "e",  'ю' to "yu", 'я' to "ya",

        // заглавные
        'А' to "A",  'Б' to "B",  'В' to "V",  'Г' to "G",  'Д' to "D",
        'Е' to "E",  'Ё' to "E",  'Ж' to "Zh",'З' to "Z",  'И' to "I",
        'Й' to "Y",  'К' to "K",  'Л' to "L",  'М' to "M",  'Н' to "N",
        'О' to "O",  'П' to "P",  'Р' to "R",  'С' to "S",  'Т' to "T",
        'У' to "U",  'Ф' to "F",  'Х' to "Kh",'Ц' to "Ts",'Ч' to "Ch",
        'Ш' to "Sh",'Щ' to "Shch",'Ъ' to "", 'Ы' to "Y",  'Ь' to "",
        'Э' to "E",  'Ю' to "Yu",'Я' to "Ya"
    )

    fun toLatin(input: String): String = buildString {
        for (ch in input) {
            val repl = map[ch]
            if (repl != null) {
                append(repl)
            } else {
                append(ch)
            }
        }
    }
}
