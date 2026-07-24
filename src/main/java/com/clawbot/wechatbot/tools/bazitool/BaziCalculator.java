package com.clawbot.wechatbot.tools.bazitool;

import com.clawbot.wechatbot.tools.bazitool.model.BaziChart;
import com.clawbot.wechatbot.tools.bazitool.model.BaziRequest;
import com.clawbot.wechatbot.tools.bazitool.model.PillarDetails;
import com.nlf.calendar.EightChar;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses lunar-java for deterministic calendar and Eight-Character calculation. */
final class BaziCalculator {
    BaziChart calculate(BaziRequest request) {
        Lunar lunar = createLunar(request);
        Solar solar = lunar.getSolar();
        EightChar eightChar = lunar.getEightChar();
        // Sect 2 treats late Zi hour's day pillar as the current civil date.
        eightChar.setSect(2);

        List<PillarDetails> pillars = List.of(
            pillar(eightChar.getYear(), eightChar.getYearGan(), eightChar.getYearZhi(),
                eightChar.getYearWuXing(), eightChar.getYearNaYin(),
                eightChar.getYearShiShenGan(), eightChar.getYearShiShenZhi()),
            pillar(eightChar.getMonth(), eightChar.getMonthGan(), eightChar.getMonthZhi(),
                eightChar.getMonthWuXing(), eightChar.getMonthNaYin(),
                eightChar.getMonthShiShenGan(), eightChar.getMonthShiShenZhi()),
            pillar(eightChar.getDay(), eightChar.getDayGan(), eightChar.getDayZhi(),
                eightChar.getDayWuXing(), eightChar.getDayNaYin(),
                eightChar.getDayShiShenGan(), eightChar.getDayShiShenZhi()),
            pillar(eightChar.getTime(), eightChar.getTimeGan(), eightChar.getTimeZhi(),
                eightChar.getTimeWuXing(), eightChar.getTimeNaYin(),
                eightChar.getTimeShiShenGan(), eightChar.getTimeShiShenZhi())
        );

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String element : List.of("木", "火", "土", "金", "水")) counts.put(element, 0);
        for (PillarDetails item : pillars) {
            item.fiveElements().codePoints()
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .filter(counts::containsKey)
                .forEach(element -> counts.compute(element, (key, count) -> count + 1));
        }

        return new BaziChart(
            solar.toYmdHms(),
            lunar.toString() + String.format(" %02d:%02d:%02d",
                request.birthTime().getHour(),
                request.birthTime().getMinute(),
                request.birthTime().getSecond()),
            lunar.getYearShengXiaoExact(),
            pillars,
            eightChar.getDayGan() + elementOfStem(eightChar.getDayGan()),
            counts
        );
    }

    private Lunar createLunar(BaziRequest request) {
        int year = request.birthYear();
        int month = request.birthMonth();
        int day = request.birthDay();
        int hour = request.birthTime().getHour();
        int minute = request.birthTime().getMinute();
        int second = request.birthTime().getSecond();
        try {
            if (request.calendarType() == BaziRequest.CalendarType.LUNAR) {
                return Lunar.fromYmdHms(
                    year, request.leapMonth() ? -month : month, day, hour, minute, second);
            }
            return Solar.fromYmdHms(year, month, day, hour, minute, second).getLunar();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("出生日期无效，农历闰月请正确设置 leap_month", e);
        }
    }

    private PillarDetails pillar(String value, String stem, String branch,
                                  String fiveElements, String naYin,
                                  String stemTenGod, List<String> branchTenGods) {
        return new PillarDetails(
            value, stem, branch, fiveElements, naYin, stemTenGod, List.copyOf(branchTenGods));
    }

    static String elementOfStem(String stem) {
        return switch (stem) {
            case "甲", "乙" -> "木";
            case "丙", "丁" -> "火";
            case "戊", "己" -> "土";
            case "庚", "辛" -> "金";
            case "壬", "癸" -> "水";
            default -> "";
        };
    }
}
