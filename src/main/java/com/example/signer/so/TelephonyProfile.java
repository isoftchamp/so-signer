package com.example.signer.so;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Android telephony identity used by the emulated Java environment.
 *
 * <p>The initial profile models one active GSM modem on Android 8.0. Keeping
 * slot and subscription routing here makes the unidbg JNI adapter independent
 * from how many SIMs are added later.</p>
 */
final class TelephonyProfile {

    static final int ANDROID_API_LEVEL = 26;
    static final int DEFAULT_SUBSCRIPTION_ID = 1;

    private final List<String> slotImeis;
    private final Map<Integer, Integer> subscriptionSlots;
    private final int defaultSubscriptionId;
    private final boolean phoneStatePermissionGranted;

    private TelephonyProfile(
            List<String> slotImeis,
            Map<Integer, Integer> subscriptionSlots,
            int defaultSubscriptionId,
            boolean phoneStatePermissionGranted) {
        this.slotImeis = slotImeis;
        this.subscriptionSlots = subscriptionSlots;
        this.defaultSubscriptionId = defaultSubscriptionId;
        this.phoneStatePermissionGranted = phoneStatePermissionGranted;
    }

    static TelephonyProfile singleSim(String imei) {
        List<String> imeis = new ArrayList<>();
        imeis.add(imei);
        return forImeis(imeis);
    }

    /**
     * Creates one active subscription per logical SIM slot. Subscription IDs
     * start at 1 and follow slot order, matching the single-SIM defaults.
     */
    static TelephonyProfile forImeis(List<String> imeis) {
        if (imeis == null || imeis.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one SIM IMEI must be configured");
        }

        List<String> completedImeis = new ArrayList<>();
        Map<Integer, Integer> subscriptions = new LinkedHashMap<>();
        for (int slotIndex = 0; slotIndex < imeis.size(); slotIndex++) {
            completedImeis.add(Imei.complete(imeis.get(slotIndex)));
            subscriptions.put(slotIndex + 1, slotIndex);
        }

        return new TelephonyProfile(
                List.copyOf(completedImeis),
                Map.copyOf(subscriptions),
                DEFAULT_SUBSCRIPTION_ID,
                true);
    }

    int getSlotCount() {
        return slotImeis.size();
    }

    int getDefaultSubscriptionId() {
        return defaultSubscriptionId;
    }

    boolean isPhoneStatePermissionGranted() {
        return phoneStatePermissionGranted;
    }

    String getImeiForDefaultSubscription() {
        return getImeiForSubscription(defaultSubscriptionId);
    }

    String getImeiForSubscription(int subscriptionId) {
        Integer slotIndex = subscriptionSlots.get(subscriptionId);
        return slotIndex == null ? null : getImeiForSlot(slotIndex);
    }

    int getSlotIndexForSubscription(int subscriptionId) {
        Integer slotIndex = subscriptionSlots.get(subscriptionId);
        return slotIndex == null ? -1 : slotIndex;
    }

    int getSubscriptionIdForSlot(int slotIndex) {
        for (Map.Entry<Integer, Integer> entry
                : subscriptionSlots.entrySet()) {
            if (entry.getValue() == slotIndex) {
                return entry.getKey();
            }
        }
        return -1;
    }

    int[] getActiveSubscriptionIds() {
        return subscriptionSlots.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .mapToInt(Map.Entry::getKey)
                .toArray();
    }

    String getImeiForSlot(int slotIndex) {
        return slotIndex < 0 || slotIndex >= slotImeis.size()
                ? null : slotImeis.get(slotIndex);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TelephonyProfile)) {
            return false;
        }
        TelephonyProfile that = (TelephonyProfile) other;
        return defaultSubscriptionId == that.defaultSubscriptionId
                && phoneStatePermissionGranted
                        == that.phoneStatePermissionGranted
                && slotImeis.equals(that.slotImeis)
                && subscriptionSlots.equals(that.subscriptionSlots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slotImeis, subscriptionSlots,
                defaultSubscriptionId, phoneStatePermissionGranted);
    }
}
