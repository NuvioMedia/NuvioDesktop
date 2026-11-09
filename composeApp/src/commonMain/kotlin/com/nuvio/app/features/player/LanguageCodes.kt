package com.nuvio.app.features.player

// Every ISO 639-2 code (bibliographic and terminology) of a language that has an ISO 639-1 code, from the ISO 639-2
// registry. Containers tag tracks with these codes, while TMDB and the language settings use ISO 639-1. "bre"
// (Breton) is left out because "br" stands for Brazilian Portuguese here, and "nob"/"nno" are in the next map.
private val Iso639Part2ToPart1 = mapOf(
    "aar" to "aa", "abk" to "ab", "afr" to "af", "aka" to "ak", "alb" to "sq", "amh" to "am", "ara" to "ar",
    "arg" to "an", "arm" to "hy", "asm" to "as", "ava" to "av", "ave" to "ae", "aym" to "ay", "aze" to "az",
    "bak" to "ba", "bam" to "bm", "baq" to "eu", "bel" to "be", "ben" to "bn", "bis" to "bi", "bod" to "bo",
    "bos" to "bs", "bul" to "bg", "bur" to "my", "cat" to "ca", "ces" to "cs", "cha" to "ch", "che" to "ce",
    "chi" to "zh", "chu" to "cu", "chv" to "cv", "cor" to "kw", "cos" to "co", "cre" to "cr", "cym" to "cy",
    "cze" to "cs", "dan" to "da", "deu" to "de", "div" to "dv", "dut" to "nl", "dzo" to "dz", "ell" to "el",
    "eng" to "en", "epo" to "eo", "est" to "et", "eus" to "eu", "ewe" to "ee", "fao" to "fo", "fas" to "fa",
    "fij" to "fj", "fin" to "fi", "fra" to "fr", "fre" to "fr", "fry" to "fy", "ful" to "ff", "geo" to "ka",
    "ger" to "de", "gla" to "gd", "gle" to "ga", "glg" to "gl", "glv" to "gv", "gre" to "el", "grn" to "gn",
    "guj" to "gu", "hat" to "ht", "hau" to "ha", "heb" to "he", "her" to "hz", "hin" to "hi", "hmo" to "ho",
    "hrv" to "hr", "hun" to "hu", "hye" to "hy", "ibo" to "ig", "ice" to "is", "ido" to "io", "iii" to "ii",
    "iku" to "iu", "ile" to "ie", "ina" to "ia", "ind" to "id", "ipk" to "ik", "isl" to "is", "ita" to "it",
    "jav" to "jv", "jpn" to "ja", "kal" to "kl", "kan" to "kn", "kas" to "ks", "kat" to "ka", "kau" to "kr",
    "kaz" to "kk", "khm" to "km", "kik" to "ki", "kin" to "rw", "kir" to "ky", "kom" to "kv", "kon" to "kg",
    "kor" to "ko", "kua" to "kj", "kur" to "ku", "lao" to "lo", "lat" to "la", "lav" to "lv", "lim" to "li",
    "lin" to "ln", "lit" to "lt", "ltz" to "lb", "lub" to "lu", "lug" to "lg", "mac" to "mk", "mah" to "mh",
    "mal" to "ml", "mao" to "mi", "mar" to "mr", "may" to "ms", "mkd" to "mk", "mlg" to "mg", "mlt" to "mt",
    "mon" to "mn", "mri" to "mi", "msa" to "ms", "mya" to "my", "nau" to "na", "nav" to "nv", "nbl" to "nr",
    "nde" to "nd", "ndo" to "ng", "nep" to "ne", "nld" to "nl", "nor" to "no", "nya" to "ny", "oci" to "oc",
    "oji" to "oj", "ori" to "or", "orm" to "om", "oss" to "os", "pan" to "pa", "per" to "fa", "pli" to "pi",
    "pol" to "pl", "por" to "pt", "pus" to "ps", "que" to "qu", "roh" to "rm", "ron" to "ro", "rum" to "ro",
    "run" to "rn", "rus" to "ru", "sag" to "sg", "san" to "sa", "sin" to "si", "slk" to "sk", "slo" to "sk",
    "slv" to "sl", "sme" to "se", "smo" to "sm", "sna" to "sn", "snd" to "sd", "som" to "so", "sot" to "st",
    "spa" to "es", "sqi" to "sq", "srd" to "sc", "srp" to "sr", "ssw" to "ss", "sun" to "su", "swa" to "sw",
    "swe" to "sv", "tah" to "ty", "tam" to "ta", "tat" to "tt", "tel" to "te", "tgk" to "tg", "tgl" to "tl",
    "tha" to "th", "tib" to "bo", "tir" to "ti", "ton" to "to", "tsn" to "tn", "tso" to "ts", "tuk" to "tk",
    "tur" to "tr", "twi" to "tw", "uig" to "ug", "ukr" to "uk", "urd" to "ur", "uzb" to "uz", "ven" to "ve",
    "vie" to "vi", "vol" to "vo", "wel" to "cy", "wln" to "wa", "wol" to "wo", "xho" to "xh", "yid" to "yi",
    "yor" to "yo", "zha" to "za", "zho" to "zh", "zul" to "zu",
)

// Codes of one variety of a macrolanguage, mapped to the macrolanguage's ISO 639-1 code, which is what TMDB uses.
// Players report them from BCP 47 tags ("nb", "cmn", "arz") and media files carry them ("nob"). Norwegian Bokmål and
// Nynorsk count as Norwegian. Chinese varieties other than Mandarin stay apart, so Cantonese ("yue") is never taken
// for Mandarin.
private val MacrolanguageMemberToPart1 = mapOf(
    "nb" to "no", "nn" to "no", "nob" to "no", "nno" to "no", "cmn" to "zh", "arb" to "ar", "arz" to "ar",
    "apc" to "ar", "ary" to "ar", "acm" to "ar", "afb" to "ar", "aeb" to "ar", "arq" to "ar", "ars" to "ar",
    "apd" to "ar", "ayl" to "ar", "pes" to "fa", "prs" to "fa", "zsm" to "ms", "zlm" to "ms", "ekk" to "et",
    "lvs" to "lv", "khk" to "mn", "npi" to "ne", "swh" to "sw", "uzn" to "uz", "uzs" to "uz", "als" to "sq",
    "azj" to "az", "azb" to "az", "ckb" to "ku", "kmr" to "ku", "sdh" to "ku", "pbt" to "ps", "pbu" to "ps",
    "ydd" to "yi", "yih" to "yi",
)

internal fun iso639Part1Code(code: String): String? {
    val lower = code.lowercase()
    return MacrolanguageMemberToPart1[lower] ?: Iso639Part2ToPart1[lower]
}
