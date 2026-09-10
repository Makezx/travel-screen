package com.laofei.travel.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

/**
 * 中文名 → 拼音（小写、去声调、ü→v）。非中文字母/数字原样保留并转小写，空格与标点忽略。
 * 例：张伟 → zhangwei；CC → cc；外婆 → wainai；老妈 → laoma。
 */
public final class PinyinUtil {

    private static final HanyuPinyinOutputFormat FMT = new HanyuPinyinOutputFormat();

    static {
        FMT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FMT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtil() {
    }

    public static String toPinyin(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) continue;
            // CJK 统一表意文字
            if (c >= 0x4E00 && c <= 0x9FFF) {
                try {
                    String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, FMT);
                    if (arr != null && arr.length > 0) {
                        sb.append(arr[0].toLowerCase());
                    }
                } catch (Exception ignored) {
                    // 单字转换失败则跳过该字
                }
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
            // 其它字符（标点等）忽略
        }
        return sb.toString();
    }
}
