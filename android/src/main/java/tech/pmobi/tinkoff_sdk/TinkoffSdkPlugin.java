package tech.pmobi.tinkoff_sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.wallet.WalletConstants;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;

import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import kotlin.Unit;
import ru.tinkoff.acquiring.sdk.AcquiringSdk;
import ru.tinkoff.acquiring.sdk.TinkoffAcquiring;
import ru.tinkoff.acquiring.sdk.exceptions.AcquiringApiException;
import ru.tinkoff.acquiring.sdk.models.AsdkState;
import ru.tinkoff.acquiring.sdk.models.Card;
import ru.tinkoff.acquiring.sdk.models.DefaultState;
import ru.tinkoff.acquiring.sdk.models.GooglePayParams;
import ru.tinkoff.acquiring.sdk.models.enums.CardStatus;
import ru.tinkoff.acquiring.sdk.models.options.screen.PaymentOptions;
import ru.tinkoff.acquiring.sdk.models.options.screen.SavedCardsOptions;
import ru.tinkoff.acquiring.sdk.payment.PaymentListener;
import ru.tinkoff.acquiring.sdk.payment.PaymentState;
import ru.tinkoff.acquiring.sdk.requests.GetCardListRequest;
import ru.tinkoff.acquiring.sdk.utils.GooglePayHelper;

public class TinkoffSdkPlugin implements MethodCallHandler, FlutterPlugin, ActivityAware {
    private static final String TAG = "tinkoff_sdk";

    private static final int PAYMENT_REQUEST_CODE = 1001;
    private static final int ATTACH_CARD_REQUEST_CODE = 1002;
    private static final int QR_REQUEST_CODE = 1003;
    private static final int REQUEST_CAMERA_CARD_SCAN = 4123;
    private static final int GOOGLE_PAY_REQUEST_CODE = 5001;

    private Activity activity;
    private MethodChannel methodChannel;
    private Result result;

    private TinkoffAcquiring tinkoffAcquiring;
    private AcquiringSdk sdk;
    private TinkoffSdkParser parser;

    private GooglePayHelper googlePayHelper;
    private boolean isGooglePayEnabled = false;
    private PaymentOptions shadowPaymentOptions;

    private PaymentListener paymentListener = new PaymentListener() {
        @Override
        public void onSuccess(long l, @Nullable String s, @Nullable String s1) {
            if (result == null) return;

            shadowPaymentOptions = null;
            result.success(createResult(-1, null).toString());
            result = null;
        }

        @Override
        public void onUiNeeded(@NotNull AsdkState asdkState) {
            tinkoffAcquiring.openPaymentScreen(
                    (FragmentActivity) activity,
                    shadowPaymentOptions,
                    PAYMENT_REQUEST_CODE,
                    asdkState
            );
        }

        @Override
        public void onError(@NotNull Throwable throwable) {
            if (result == null) return;

            final String locMessage = throwable.getLocalizedMessage();
            final String message = throwable.getMessage();
            result.error(locMessage, message, null);
            result = null;
        }

        @Override
        public void onStatusChanged(@Nullable PaymentState paymentState) {
            final String state = paymentState != null ? paymentState.toString() : "null";
            Log.i(TAG, "PaymentListener onStatusChanged: " + state);
        }
    };

    private PluginRegistry.ActivityResultListener activityResultListener = new PluginRegistry.ActivityResultListener() {
        @Override
        public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
            if (result != null) {
                if (requestCode == PAYMENT_REQUEST_CODE) {
                    result.success(createResult(resultCode, data).toString());
                    shadowPaymentOptions = null;
                    result = null;
                } else if (requestCode == ATTACH_CARD_REQUEST_CODE) {
                    result.success(null);
                    result = null;
                } else if (requestCode == QR_REQUEST_CODE) {
                    result.success(null);
                    result = null;
                } else if (requestCode == GOOGLE_PAY_REQUEST_CODE) {
                    handleGooglePayResult(resultCode, data);
                }
            }
            return false;
        }
    };

    private JSONObject createResult(int resultCode, Intent data) {
        JSONObject json = new JSONObject();
        String message;
        final boolean success = resultCode == -1;

        if (data != null && !success) {
            final Bundle bundle = data.getExtras();
            final AcquiringApiException exception = (AcquiringApiException) bundle.get(TinkoffAcquiring.EXTRA_ERROR);
            message = exception != null
                    ? exception.getLocalizedMessage()
                    : "Неизвестная ошибка";
        } else {
            message = success ? "Оплата прошла успешно" : "Закрытие экрана оплаты";
        }

        try {
            json.put("success", success);
            json.put("isError", resultCode > 0);
            json.put("message", message);
        } catch (JSONException ex) {
            ex.printStackTrace();
        }
        return json;
    }

    // Supporting FlutterEmbedding v1
    public static void registerWith(Registrar registrar) {
        final TinkoffSdkPlugin instance = new TinkoffSdkPlugin();
        registrar.addActivityResultListener(instance.activityResultListener);
        instance.activity = registrar.activity();
        instance.onAttachedToEngine(registrar.context(), registrar.messenger());
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if (this.result != null) return;
        if (!(activity instanceof FragmentActivity)) {
            result.error(
                    "no_fragment_activity",
                    "plugin requires activity to be a FragmentActivity.",
                    null);
            return;
        }

        this.result = result;

        switch (call.method) {
            case "activate":
                handleActivate(call);
                break;
            case "cardList":
                handleCardList(call);
                break;
            case "openPaymentScreen":
                handleOpenPaymentScreen(call);
                break;
            case "attachCardScreen":
                handleAttachCardScreen(call);
                break;
            case "showQrScreen":
                handleShowQrScreen(call);
                break;
            case "openNativePayment":
                handleOpenNativePayment(call);
                break;
            case "startCharge":
                handleStartCharge(call);
                break;
            case "isNativePayAvailable":
                handleIsNativePayAvailable(call);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void handleActivate(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;
            // Get activation parameters.
            final String terminalKey = (String) arguments.get("terminalKey");
            final String password = (String) arguments.get("password");
            final String publicKey = (String) arguments.get("publicKey");
            final boolean nativePay = (boolean) arguments.get("nativePay");
            final boolean isDeveloperMode = (boolean) arguments.get("isDeveloperMode");
            final boolean isDebug = (boolean) arguments.get("isDebug");
            final String language = (String) arguments.get("language");

            AcquiringSdk.AsdkLogger.setDeveloperMode(isDeveloperMode);
            AcquiringSdk.AsdkLogger.setDebug(isDebug);

            parser = new TinkoffSdkParser(language);
            tinkoffAcquiring = new TinkoffAcquiring(terminalKey, publicKey);
            sdk = new AcquiringSdk(terminalKey, publicKey);
            sdk.init(initRequest -> Unit.INSTANCE);

            if (nativePay) {
                setupGooglePlay(terminalKey);
            }

            result.success(true);
        } catch (Exception e) {
            result.success(false);
        }
        result = null;
    }

    private void setupGooglePlay(@NonNull String terminalKey) {
        final GooglePayParams googleParams = new GooglePayParams(
                terminalKey,
                false,
                false,
                AcquiringSdk.AsdkLogger.isDebug()
                        ? WalletConstants.ENVIRONMENT_TEST
                        : WalletConstants.ENVIRONMENT_PRODUCTION
        );

        googlePayHelper = new GooglePayHelper(googleParams);

        Context context = activity.getApplicationContext();
        googlePayHelper.initGooglePay(
                context,
                isReady -> {
                    isGooglePayEnabled = isReady;
                    return Unit.INSTANCE;
                }
        );
    }

    private void handleCardList(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;
            final String customerKey = (String) arguments.get("customerKey");

            final GetCardListRequest request = sdk.getCardList(r -> {
                r.setCustomerKey(customerKey);
                return Unit.INSTANCE;
            });

            Thread thread = new Thread(() -> {
                request.execute(
                        response -> {
                            activity.runOnUiThread(() -> {
                                final Card[] cards = response.getCards();
                                final ArrayList<String> cardsList = new ArrayList();

                                for (final Card card : cards) {
                                    if (card.getStatus() == CardStatus.ACTIVE) {
                                        JSONObject json = new JSONObject();
                                        try {
                                            json.put("cardId", card.getCardId());
                                            json.put("pan", card.getPan());
                                            json.put("expDate", card.getExpDate());
                                            cardsList.add(json.toString());
                                        } catch (JSONException ex) {
                                            ex.printStackTrace();
                                        }
                                    }
                                }

                                result.success(cardsList);
                                result = null;
                            });
                            return Unit.INSTANCE;
                        },
                        e -> {
                            activity.runOnUiThread(() -> {
                                result.success(new ArrayList());
                                result = null;
                            });

                            return Unit.INSTANCE;
                        }
                );
            });
            thread.start();

        } catch (Exception e) {
            e.printStackTrace();
            result.success(new ArrayList());
            result = null;
        }
    }

    private void handleOpenPaymentScreen(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;
            final PaymentOptions paymentOptions = parser.createPaymentOptions(arguments);

            tinkoffAcquiring.openPaymentScreen(
                    (FragmentActivity) activity,
                    paymentOptions,
                    PAYMENT_REQUEST_CODE,
                    DefaultState.INSTANCE
            );

        } catch (Exception e) {
            result.error("error", e.getMessage(), null);
        }
    }

    private void handleAttachCardScreen(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;
            final String customerKey = (String) arguments.get("customerKey");
            final String cardId = (String) arguments.get("cardId");
            final boolean isOnlyAttach = (Boolean) arguments.get("isOnlyAttach");

            tinkoffAcquiring.openAttachCardScreen(
                    (FragmentActivity) activity,
                    customerKey,
                    cardId,
                    isOnlyAttach,
                    ATTACH_CARD_REQUEST_CODE
            );

        } catch (Exception e) {
            result.error("error", e.getMessage(), null);
        }
    }

    private void handleShowQrScreen(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;

            final String qrCode = (String) arguments.get("qrCode");
            final String token = (String) arguments.get("token");
            final String style = (String) arguments.get("style");

            tinkoffAcquiring.openQrScreen(
                    (FragmentActivity) activity,
                    qrCode,
                    token,
                    style,
                    QR_REQUEST_CODE
            );
        } catch (Exception e) {
            result.error("error", e.getMessage(), null);
        }
    }

    private void handleOpenNativePayment(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;

            final String customerKey = (String) arguments.get("customerKey");

            sdk.startPayment(
                    customerKey,
                    new PaymentListener() {
                        @Override
                        public void onError(Throwable throwable) {
                            result.error("error", throwable.getMessage(), null);
                        }

                        @Override
                        public void onStatusChanged(PaymentState state) {
                            Log.i(TAG, "Payment state: " + state);
                        }

                        @Override
                        public void onSuccess(long amount, String paymentToken, String rawResponse) {
                            result.success(createResult(-1, null).toString());
                        }

                        @Override
                        public void onUiNeeded(AsdkState asdkState) {
                            tinkoffAcquiring.openPaymentScreen(
                                    (FragmentActivity) activity,
                                    shadowPaymentOptions,
                                    PAYMENT_REQUEST_CODE,
                                    asdkState
                            );
                        }
                    }
            );
        } catch (Exception e) {
            result.error("error", e.getMessage(), null);
        }
    }

    private void handleStartCharge(MethodCall call) {
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> arguments = (Map<String, Object>) call.arguments;

            final long amount = (Long) arguments.get("amount");
            final String customerKey = (String) arguments.get("customerKey");

            sdk.startPayment(
                    amount,
                    customerKey,
                    new PaymentListener() {
                        @Override
                        public void onSuccess(long amount, String paymentToken, String rawResponse) {
                            result.success(createResult(-1, null).toString());
                        }

                        @Override
                        public void onUiNeeded(AsdkState asdkState) {
                            tinkoffAcquiring.openPaymentScreen(
                                    (FragmentActivity) activity,
                                    shadowPaymentOptions,
                                    PAYMENT_REQUEST_CODE,
                                    asdkState
                            );
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            result.error("error", throwable.getMessage(), null);
                        }
                    }
            );
        } catch (Exception e) {
            result.error("error", e.getMessage(), null);
        }
    }

    private void handleIsNativePayAvailable(MethodCall call) {
        result.success(isGooglePayEnabled);
        result = null;
    }

    @Override
    public void onAttachedToEngine(@NonNull Context context, @NonNull BinaryMessenger messenger) {
        methodChannel = new MethodChannel(messenger, "tinkoff_sdk");
        methodChannel.setMethodCallHandler(this);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // No implementation needed
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        this.activity = null;
    }
}
