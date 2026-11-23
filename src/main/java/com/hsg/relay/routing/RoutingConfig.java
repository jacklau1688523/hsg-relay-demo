package com.hsg.relay.routing;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** All monetary balances are in their named currency; limits and targets are USD equivalents. */
public record RoutingConfig(Map<String,BigDecimal> hkdRates, List<Reach> reaches, List<Balance> balances,
 List<Fee> fees, List<FxWindow> fxWindows, List<Preference> preferences, List<Agreement> agreements,
 List<Weight> weights, Map<String,BigDecimal> todayUsd, Map<String,BigDecimal> monthUsd,
 Map<String,Long> monthCount, List<LocalDate> hongKongHolidays) {
 public record Reach(String provider,String currency,String country,String region,List<String> bics,List<String> charges,
  String zone,LocalTime cutoff,int offsetDays,BigDecimal perPaymentUsd,BigDecimal dailyUsd,List<LocalDate> holidays,Boolean enabled) {
  public boolean active(){return !Boolean.FALSE.equals(enabled);}
 }
 public RoutingConfig withReaches(List<Reach> rows){return new RoutingConfig(hkdRates,rows,balances,fees,fxWindows,preferences,agreements,weights,todayUsd,monthUsd,monthCount,hongKongHolidays);}
 public RoutingConfig withBalances(List<Balance> rows){return new RoutingConfig(hkdRates,reaches,rows,fees,fxWindows,preferences,agreements,weights,todayUsd,monthUsd,monthCount,hongKongHolidays);}
 public RoutingConfig withFees(List<Fee> rows){return new RoutingConfig(hkdRates,reaches,balances,rows,fxWindows,preferences,agreements,weights,todayUsd,monthUsd,monthCount,hongKongHolidays);}
 public RoutingConfig withFxWindows(List<FxWindow> rows){return new RoutingConfig(hkdRates,reaches,balances,fees,rows,preferences,agreements,weights,todayUsd,monthUsd,monthCount,hongKongHolidays);}
 public RoutingConfig withPreferences(List<Preference> rows){return new RoutingConfig(hkdRates,reaches,balances,fees,fxWindows,rows,agreements,weights,todayUsd,monthUsd,monthCount,hongKongHolidays);}
 public RoutingConfig withTodayUsd(Map<String,BigDecimal> rows){return new RoutingConfig(hkdRates,reaches,balances,fees,fxWindows,preferences,agreements,weights,rows,monthUsd,monthCount,hongKongHolidays);}
 public record Balance(String provider,String currency,BigDecimal balance,BigDecimal reserved) {}
 public record Fee(String provider,String currency,String charge,String feeCurrency,BigDecimal fixed,BigDecimal rate,
  BigDecimal minimum,BigDecimal maximum,BigDecimal cable,BigDecimal intermediary) {}
 public record FxWindow(String provider,String debit,String payment,List<String> days,LocalTime start,LocalTime end,
  BigDecimal spreadBps,BigDecimal extraBps,BigDecimal maxUsd) {}
 public record Preference(String provider,String currency,String country,String region,BigDecimal minUsd,BigDecimal maxUsd,
  String paymentType,String segment,int points) {}
 public record Agreement(String provider,String metric,BigDecimal monthlyTarget) {}
 public record Weight(String paymentType,String segment,int cost,int speed,int preference,int agreement) {}
}
