package eu.siacs.conversations;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.EmojiInitializationService;
import eu.siacs.conversations.ui.AppLockActivity;
import eu.siacs.conversations.ui.util.AppLockManager;
import eu.siacs.conversations.ui.util.SettingsUtils;
import eu.siacs.conversations.utils.ExceptionHelper;
import java.security.Security;
import java.util.Collection;
import java.util.Objects;
import org.conscrypt.Conscrypt;

public class Conversations extends Application implements Application.ActivityLifecycleCallbacks {

    @SuppressLint("StaticFieldLeak")
    private static Context CONTEXT;

    public static Context getContext() {
        return Conversations.CONTEXT;
    }

    private final Supplier<Collection<DatabaseBackend.AccountWithOptions>>
            accountWithOptionsSupplier =
                    () -> {
                        final var stopwatch = Stopwatch.createStarted();
                        final var accounts =
                                DatabaseBackend.getInstance(Conversations.this)
                                        .getAccountWithOptions();
                        Log.d(
                                Config.LOGTAG,
                                "fetching accounts from database in " + stopwatch.stop());
                        return accounts;
                    };
    private Supplier<Collection<DatabaseBackend.AccountWithOptions>> accountWithOptions =
            Suppliers.memoize(accountWithOptionsSupplier);

    private int startedUiActivities;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        installSecurityProvider();
        CONTEXT = this.getApplicationContext();
        EmojiInitializationService.execute(getApplicationContext());
        ExceptionHelper.init(getApplicationContext());
        SettingsUtils.applyThemeSettings(this);
    }

    private static void installSecurityProvider() {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (final Throwable throwable) {
            Log.e(Config.LOGTAG, "could not install security provider", throwable);
        }
    }

    public static Conversations getInstance(final Context context) {
        if (context.getApplicationContext() instanceof Conversations c) {
            return c;
        }
        throw new IllegalStateException("Application is not Conversations");
    }

    public void resetAccounts() {
        this.accountWithOptions = Suppliers.memoize(accountWithOptionsSupplier);
    }

    public Collection<DatabaseBackend.AccountWithOptions> getAccounts() {
        return this.accountWithOptions.get();
    }

    public boolean hasEnabledAccount() {
        return Iterables.any(
                getAccounts(),
                a ->
                        !Objects.requireNonNull(a).isOptionSet(Account.OPTION_DISABLED)
                                && !a.isOptionSet(Account.OPTION_SOFT_DISABLED));
    }

    @Override
    public synchronized void onActivityStarted(final Activity activity) {
        if (!(activity instanceof AppLockActivity)) {
            startedUiActivities++;
        }
    }

    @Override
    public synchronized void onActivityStopped(final Activity activity) {
        if (activity instanceof AppLockActivity) {
            return;
        }
        startedUiActivities = Math.max(0, startedUiActivities - 1);
        if (startedUiActivities == 0 && !activity.isChangingConfigurations()) {
            AppLockManager.onAppBackground(this);
        }
    }

    @Override
    public void onActivityCreated(final Activity activity, final android.os.Bundle state) {}

    @Override
    public void onActivityResumed(final Activity activity) {}

    @Override
    public void onActivityPaused(final Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(
            final Activity activity, final android.os.Bundle state) {}

    @Override
    public void onActivityDestroyed(final Activity activity) {}
}
