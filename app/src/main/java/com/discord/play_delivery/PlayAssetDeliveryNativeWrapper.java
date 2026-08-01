package com.discord.play_delivery;

@SuppressWarnings("unused")
public final class PlayAssetDeliveryNativeWrapper {
    private static volatile String krispAssetPackLocation = "";

    private PlayAssetDeliveryNativeWrapper() {}

    public static String getKrispAssetPackLocation() {
        return krispAssetPackLocation;
    }

    public static void setKrispAssetPackLocation(String location) {
        krispAssetPackLocation = location;
    }
}
