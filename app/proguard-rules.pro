# ProGuard / R8 configuration for EnphaseLive

# Keep data classes and models used in serialization
-keepclassmembers class com.saver.enphaselive.RealtimePowerSample {
    <fields>;
    <init>(...);
}

# Keep custom views used in layout or instantiated reflectively
-keep public class com.saver.enphaselive.EnergyFlowView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep public class com.saver.enphaselive.HistoryChartView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep public class com.saver.enphaselive.RealtimeChartView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve line numbers for readable crash stacktraces
-keepattributes SourceFile,LineNumberTable
