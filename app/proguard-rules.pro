# UltimateFetcher ProGuard / R8 Rules
-keep class com.axion.ultimatefetcher.data.database.entities.** { *; }
-keep class com.axion.ultimatefetcher.domain.models.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
