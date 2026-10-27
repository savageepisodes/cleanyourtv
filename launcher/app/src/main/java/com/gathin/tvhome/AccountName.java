package com.gathin.tvhome;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

final class AccountName {
    private static final String TAG = "TvHome.Account";

    private AccountName() {}

    static String get(Context context) {
        // 1. Try AccountManager
        try {
            AccountManager am = AccountManager.get(context);
            Account[] accounts = am.getAccountsByType("com.google");
            if (accounts == null || accounts.length == 0) {
                accounts = am.getAccounts();
            }
            if (accounts != null && accounts.length > 0) {
                for (Account account : accounts) {
                    if (account.name != null && account.name.contains("@")) {
                        // Check if Google user data has first name
                        try {
                            String given = am.getUserData(account, "given_name");
                            if (given == null || given.isEmpty()) given = am.getUserData(account, "firstName");
                            if (given == null || given.isEmpty()) given = am.getUserData(account, "name");
                            if (given != null && !given.isEmpty() && !given.contains("@")) {
                                Log.i(TAG, "Found first name from user data: " + given);
                                return capitalize(given.trim());
                            }
                        } catch (Exception ignored) {}

                        // Easiest route: strip before '@'
                        String parsed = parseNameFromEmail(account.name);
                        if (!parsed.isEmpty()) {
                            Log.i(TAG, "Resolved account name from email: " + parsed);
                            return parsed;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "AccountManager query error: " + e.getMessage());
        }

        // 2. Fallback to device_name in Global settings (e.g. "ADEK_TCL" -> "Adek")
        try {
            String deviceName = Settings.Global.getString(context.getContentResolver(), "device_name");
            if (deviceName != null && !deviceName.isEmpty()) {
                if (deviceName.contains("_")) {
                    deviceName = deviceName.substring(0, deviceName.indexOf('_'));
                } else if (deviceName.contains("-")) {
                    deviceName = deviceName.substring(0, deviceName.indexOf('-'));
                } else if (deviceName.contains(" ")) {
                    deviceName = deviceName.substring(0, deviceName.indexOf(' '));
                }
                if (!deviceName.isEmpty()) {
                    return capitalize(deviceName.trim());
                }
            }
        } catch (Exception ignored) {}

        return "";
    }

    static String parseNameFromEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        String name = at > 0 ? email.substring(0, at) : email;
        if (name.contains(".")) {
            name = name.substring(0, name.indexOf('.'));
        } else if (name.contains("_")) {
            name = name.substring(0, name.indexOf('_'));
        }
        name = name.replaceAll("\\d+$", "");
        return capitalize(name.trim());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1).toLowerCase(Locale.ROOT) : "");
    }
}
