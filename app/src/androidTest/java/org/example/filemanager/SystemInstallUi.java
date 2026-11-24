package org.example.filemanager;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.Assume;
import static org.junit.Assert.*;

/** Exercises a real installer session even on a device that cannot open APK files via VIEW. */
final class SystemInstallUi extends Instrumentation.ActivityMonitor implements AutoCloseable {
    static final String FIXTURE = "org.example.installfixture";
    private static final String LABEL = "File Manager Install Fixture"; // label in install-fixture.apk
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context;
    private final UiDevice device;
    private final PackageInstaller installer;
    private final Set<Integer> existing = new HashSet<>();
    private final long installedBefore;
    private volatile boolean settingsForThisApp;
    private String sourceSettingsPackage;
    private int confirmationSession = -1;

    SystemInstallUi(Context context, UiDevice device) {
        this.context = context;
        this.device = device;
        installer = context.getPackageManager().getPackageInstaller();
        for (var session : installer.getMySessions()) existing.add(session.getSessionId());
        installedBefore = installedAt();
        instrumentation.addMonitor(this);
    }

    @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
        if (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(intent.getAction())
                && ("package:" + context.getPackageName()).equals(intent.getDataString())) settingsForThisApp = true;
        if ((Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_INSTALL_PACKAGE.equals(intent.getAction()))
                && intent.getData() != null && (context.getPackageName() + ".files").equals(intent.getData().getAuthority()))
            throw new ActivityNotFoundException("Fixture device has no handler for an APK file URI");
        return null; // The real settings and system-provided confirmation intent run normally.
    }

    void requireInitialSource(boolean allowed) {
        boolean hostPrepared = "true".equals(InstrumentationRegistry.getArguments().getString("installSourceTransitions"));
        // Revocation can kill the instrumented process. The host prepares and restores the initial state.
        Assume.assumeTrue("Run each source transition separately with -e installSourceTransitions true and REQUEST_INSTALL_PACKAGES denied before launch",
                allowed || hostPrepared);
        boolean actual = context.getPackageManager().canRequestPackageInstalls();
        if (hostPrepared) assertEquals("Unexpected initial install-source permission; check host setup", allowed, actual);
        else Assume.assumeTrue("Requires REQUEST_INSTALL_PACKAGES already allowed before instrumentation", actual);
    }

    void openSourceSettings() {
        var openSettings = By.pkg(context.getPackageName()).text("打开设置").enabled(true);
        await("Did not request the install source permission", () -> settingsForThisApp || device.hasObject(openSettings));
        if (!settingsForThisApp) device.findObject(openSettings).click();
        await("Did not open source settings for " + context.getPackageName(), () -> settingsForThisApp && externalSystemWindow());
        sourceSettingsPackage = device.getCurrentPackageName();
        settingsForThisApp = false; // A second visit must observe a fresh settings intent.
    }

    void grantSourceInSettings() {
        assertNotNull("Open this app's source settings first", sourceSettingsPackage);
        assertEquals("Left the source settings page", sourceSettingsPackage, device.getCurrentPackageName());
        assertFalse("Source is already allowed", context.getPackageManager().canRequestPackageInstalls());
        var switches = device.wait(Until.findObjects(By.pkg(sourceSettingsPackage).checkable(true).enabled(true)), 10000);
        assertNotNull("No install-source switch in settings", switches);
        // Some settings pages expose both the preference row and its actual Switch as checkable.
        var widgets = switches.stream().filter(node -> "android.widget.Switch".equals(node.getClassName())).toList();
        var controls = widgets.isEmpty() ? switches : widgets;
        assertEquals("Cannot identify a unique install-source switch", 1, controls.size());
        var sourceSwitch = controls.get(0);
        assertFalse("Refusing to revoke the install-source permission", sourceSwitch.isChecked());
        sourceSwitch.click();
        await("Settings did not grant the install-source permission", () -> context.getPackageManager().canRequestPackageInstalls());
    }

    void awaitConfirmationAndCancel() {
        await("Expected a new sealed session and a system confirmation showing " + LABEL, () -> {
            if (!confirmationVisible()) return false;
            var matches = installer.getMySessions().stream().filter(s -> !existing.contains(s.getSessionId()) && s.isSealed()
                    && (s.getAppPackageName() == null || FIXTURE.equals(s.getAppPackageName()))).toList();
            if (matches.size() != 1) return false;
            confirmationSession = matches.get(0).getSessionId(); return true;
        });
        assertEquals("Confirmation must precede any installation", installedBefore, installedAt());
        // Prefer the explicit system Cancel button; Back can move some installers to the background.
        String pkg = device.getCurrentPackageName();
        var cancel = device.findObject(By.pkg(pkg).res("android", "button2").enabled(true));
        if (cancel != null) cancel.click(); else device.pressBack();
        await("Cancelled installation left an owned session", () -> installer.getMySessions().stream()
                .noneMatch(s -> s.getSessionId() == confirmationSession));
        assertEquals("Cancelling changed the installed fixture", installedBefore, installedAt());
    }

    void assertNoNewSession() {
        for (var session : installer.getMySessions())
            assertTrue("Unexpected session " + session.getSessionId(), existing.contains(session.getSessionId()));
        assertEquals("Source denial changed the installed fixture", installedBefore, installedAt());
    }

    private boolean isFixtureSession(PackageInstaller.SessionInfo info) {
        return !existing.contains(info.getSessionId()) && FIXTURE.equals(info.getAppPackageName());
    }

    private boolean confirmationVisible() {
        String pkg = device.getCurrentPackageName();
        if (!systemPackage(pkg)) return false;
        boolean fixture = device.hasObject(By.pkg(pkg).textContains(LABEL)) || device.hasObject(By.pkg(pkg).descContains(LABEL))
                || device.hasObject(By.pkg(pkg).textContains(FIXTURE));
        return fixture && (device.hasObject(By.pkg(pkg).res("android", "button1").enabled(true))
                && device.hasObject(By.pkg(pkg).res("android", "button2").enabled(true))
                || device.findObjects(By.pkg(pkg).clazz(android.widget.Button.class).enabled(true).clickable(true)).size() >= 2);
    }

    boolean externalSystemWindow() { return systemPackage(device.getCurrentPackageName()); }

    private boolean systemPackage(String pkg) {
        if (pkg == null || pkg.equals(context.getPackageName())) return false;
        PackageManager pm = context.getPackageManager();
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    || pm.checkPermission(Manifest.permission.INSTALL_PACKAGES, pkg) == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    private long installedAt() {
        try { return context.getPackageManager().getPackageInfo(FIXTURE, 0).lastUpdateTime; }
        catch (PackageManager.NameNotFoundException absent) { return -1; }
    }

    static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 20000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }

    @Override public void close() {
        instrumentation.removeMonitor(this);
        for (var session : installer.getMySessions()) if (isFixtureSession(session) || session.getSessionId() == confirmationSession) {
            try { installer.abandonSession(session.getSessionId()); }
            catch (SecurityException ignored) { /* The cancellation may have just finished. */ }
        }
    }
}
