package com.translator.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Язык для системы перевода.
 *
 * @param code ISO-код для логики (например "en", "ru").
 * @param nameEn название на английском — подставляется в SYSTEM_INSTRUCTION.
 * @param nameRu название на русском — отображается в UI.
 * @param flag emoji-флаг для быстрой визуальной идентификации.
 */
@Immutable
data class Language(
    val code: String,
    val nameEn: String,
    val nameRu: String,
    val flag: String
)

/**
 * 100 самых распространённых языков мира (Ethnologue 2025).
 * Порядок соответствует рейтингу по числу говорящих.
 */
object Languages {

    val ALL: List<Language> = listOf(
        Language("en",      "English",                  "Английский",                  "🇬🇧"),
        Language("zh",      "Mandarin Chinese",         "Мандаринский",                "🇨🇳"),
        Language("hi",      "Hindi",                    "Хинди",                       "🇮🇳"),
        Language("es",      "Spanish",                  "Испанский",                   "🇪🇸"),
        Language("ar",      "Standard Arabic",          "Стандартный арабский",        "🇸🇦"),
        Language("fr",      "French",                   "Французский",                 "🇫🇷"),
        Language("bn",      "Bengali",                  "Бенгальский",                 "🇧🇩"),
        Language("pt",      "Portuguese",               "Португальский",               "🇵🇹"),
        Language("ru",      "Russian",                  "Русский",                     "🇷🇺"),
        Language("id",      "Indonesian",               "Индонезийский",               "🇮🇩"),
        Language("ur",      "Urdu",                     "Урду",                        "🇵🇰"),
        Language("de",      "German",                   "Немецкий",                    "🇩🇪"),
        Language("ja",      "Japanese",                 "Японский",                    "🇯🇵"),
        Language("pcm",     "Nigerian Pidgin",          "Нигерийский пиджин",          "🇳🇬"),
        Language("arz",     "Egyptian Arabic",          "Египетский арабский",         "🇪🇬"),
        Language("mr",      "Marathi",                  "Маратхи",                     "🇮🇳"),
        Language("vi",      "Vietnamese",               "Вьетнамский",                 "🇻🇳"),
        Language("te",      "Telugu",                   "Телугу",                      "🇮🇳"),
        Language("ha",      "Hausa",                    "Хауса",                       "🇳🇬"),
        Language("tr",      "Turkish",                  "Турецкий",                    "🇹🇷"),
        Language("pnb",     "Western Punjabi",          "Западный панджаби",           "🇵🇰"),
        Language("sw",      "Swahili",                  "Суахили",                     "🇰🇪"),
        Language("tl",      "Tagalog",                  "Тагальский",                  "🇵🇭"),
        Language("ta",      "Tamil",                    "Тамильский",                  "🇮🇳"),
        Language("yue",     "Cantonese",                "Кантонский",                  "🇭🇰"),
        Language("wuu",     "Wu Chinese",               "У (Шанхайский)",              "🇨🇳"),
        Language("fa",      "Iranian Persian",          "Иранский персидский",         "🇮🇷"),
        Language("ko",      "Korean",                   "Корейский",                   "🇰🇷"),
        Language("th",      "Thai",                     "Тайский",                     "🇹🇭"),
        Language("jv",      "Javanese",                 "Яванский",                    "🇮🇩"),
        Language("it",      "Italian",                  "Итальянский",                 "🇮🇹"),
        Language("gu",      "Gujarati",                 "Гуджарати",                   "🇮🇳"),
        Language("apc",     "Levantine Arabic",         "Левантийский арабский",       "🇱🇧"),
        Language("am",      "Amharic",                  "Амхарский",                   "🇪🇹"),
        Language("kn",      "Kannada",                  "Каннада",                     "🇮🇳"),
        Language("bho",     "Bhojpuri",                 "Бходжпури",                   "🇮🇳"),
        Language("apd",     "Sudanese Arabic",          "Суданский арабский",          "🇸🇩"),
        Language("nan",     "Southern Min (Hokkien)",   "Южный минь",                  "🇨🇳"),
        Language("cjy",     "Jin Chinese",              "Цзинь",                       "🇨🇳"),
        Language("yo",      "Yoruba",                   "Йоруба",                      "🇳🇬"),
        Language("myx",     "Masaba",                   "Масаба",                      "🇺🇬"),
        Language("hak",     "Hakka Chinese",            "Хакка",                       "🇨🇳"),
        Language("my",      "Burmese",                  "Бирманский",                  "🇲🇲"),
        Language("pl",      "Polish",                   "Польский",                    "🇵🇱"),
        Language("or",      "Odia",                     "Ория",                        "🇮🇳"),
        Language("uk",      "Ukrainian",                "Украинский",                  "🇺🇦"),
        Language("ml",      "Malayalam",                "Малаялам",                    "🇮🇳"),
        Language("su",      "Sundanese",                "Сунданский",                  "🇮🇩"),
        Language("ps",      "Pashto",                   "Пушту",                       "🇦🇫"),
        Language("arq",     "Algerian Arabic",          "Алжирский арабский",          "🇩🇿"),
        Language("sd",      "Sindhi",                   "Синдхи",                      "🇵🇰"),
        Language("pa",      "Eastern Punjabi",          "Восточный панджаби",          "🇮🇳"),
        Language("ig",      "Igbo",                     "Игбо",                        "🇳🇬"),
        Language("prs",     "Dari",                     "Дари",                        "🇦🇫"),
        Language("ne",      "Nepali",                   "Непальский",                  "🇳🇵"),
        Language("ms",      "Malay",                    "Малайский",                   "🇲🇾"),
        Language("uz",      "Northern Uzbek",           "Северный узбекский",          "🇺🇿"),
        Language("zu",      "Zulu",                     "Зулу",                        "🇿🇦"),
        Language("aec",     "Saidi Arabic",             "Саидский арабский",           "🇪🇬"),
        Language("pbu",     "Northern Pashto",          "Северный пушту",              "🇦🇫"),
        Language("gax",     "West Central Oromo",       "Западный оромо",              "🇪🇹"),
        Language("skr",     "Saraiki",                  "Сирайки",                     "🇵🇰"),
        Language("nl",      "Dutch",                    "Нидерландский",               "🇳🇱"),
        Language("so",      "Somali",                   "Сомалийский",                 "🇸🇴"),
        Language("ro",      "Romanian",                 "Румынский",                   "🇷🇴"),
        Language("as",      "Assamese",                 "Ассамский",                   "🇮🇳"),
        Language("gan",     "Gan Chinese",              "Гань",                        "🇨🇳"),
        Language("pbt",     "Southern Pashto",          "Южный пушту",                 "🇦🇫"),
        Language("ceb",     "Cebuano",                  "Себуанский",                  "🇵🇭"),
        Language("kk",      "Kazakh",                   "Казахский",                   "🇰🇿"),
        Language("mag",     "Magahi",                   "Магахи",                      "🇮🇳"),
        Language("ars",     "Najdi Arabic",             "Недждийский арабский",        "🇸🇦"),
        Language("si",      "Sinhala",                  "Сингальский",                 "🇱🇰"),
        Language("acm",     "Mesopotamian Arabic",      "Месопотамский арабский",      "🇮🇶"),
        Language("xh",      "Xhosa",                    "Коса",                        "🇿🇦"),
        Language("km",      "Khmer",                    "Кхмерский",                   "🇰🇭"),
        Language("af",      "Afrikaans",                "Африкаанс",                   "🇿🇦"),
        Language("fuv",     "Nigerian Fulfulde",        "Нигерийский фульфульде",      "🇳🇬"),
        Language("mai",     "Maithili",                 "Майтхили",                    "🇮🇳"),
        Language("wo",      "Wolof",                    "Волоф",                       "🇸🇳"),
        Language("kmr",     "Northern Kurdish",         "Северный курдский",           "🇹🇷"),
        Language("hne",     "Chhattisgarhi",            "Чхаттисгархи",                "🇮🇳"),
        Language("rw",      "Kinyarwanda",              "Киньяруанда",                 "🇷🇼"),
        Language("tts",     "Northeastern Thai",        "Северо-восточный тайский",    "🇹🇭"),
        Language("ny",      "Chichewa",                 "Чева",                        "🇲🇼"),
        Language("bm",      "Bambara",                  "Бамананкан",                  "🇲🇱"),
        Language("sn",      "Shona",                    "Шона",                        "🇿🇼"),
        Language("azb",     "Southern Azerbaijani",     "Южный азербайджанский",       "🇮🇷"),
        Language("tn",      "Tswana",                   "Сетсвана",                    "🇧🇼"),
        Language("nso",     "Northern Sotho",           "Северный сото",               "🇿🇦"),
        Language("bar",     "Bavarian",                 "Баварский",                   "🇩🇪"),
        Language("ht",      "Haitian Creole",           "Гаитянский креольский",       "🇭🇹"),
        Language("ug",      "Uyghur",                   "Уйгурский",                   "🇨🇳"),
        Language("st",      "Southern Sotho",           "Южный сото",                  "🇱🇸"),
        Language("el",      "Greek",                    "Греческий",                   "🇬🇷"),
        Language("sv",      "Swedish",                  "Шведский",                    "🇸🇪"),
        Language("ctg",     "Chittagonian",             "Читтагонский",                "🇧🇩"),
        Language("rn",      "Rundi",                    "Рунди",                       "🇧🇮"),
        Language("dyu",     "Dyula",                    "Дьюла",                       "🇧🇫"),
        Language("mn",      "Mongolian",                "Монгольский",                 "🇲🇳"),
        Language("cs",      "Czech",                    "Чешский",                     "🇨🇿"),
        Language("he",      "Hebrew",                   "Иврит",                       "🇮🇱"),
        Language("fi",      "Finnish",                  "Финский",                     "🇫🇮")
    )

    val DEFAULT_SOURCE: Language = ALL.first { it.code == "ru" }
    val DEFAULT_TARGET: Language = ALL.first { it.code == "de" }

    fun byCode(code: String): Language =
        ALL.firstOrNull { it.code == code } ?: DEFAULT_SOURCE
}