package com.pagopa.reactnativezendesk;

import android.app.Activity;
import android.content.Context;

import android.content.Intent;
import androidx.annotation.NonNull;

import com.facebook.react.bridge.ActivityEventListener;
import android.util.Log;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.Callback;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.bridge.ReadableMap;
import com.zendesk.service.ErrorResponse;
import com.zendesk.service.ZendeskCallback;

import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import zendesk.chat.Chat;
import zendesk.chat.ChatConfiguration;
import zendesk.chat.ChatEngine;
import zendesk.chat.ChatProvider;
import zendesk.chat.ProfileProvider;
import zendesk.chat.PushNotificationsProvider;
import zendesk.chat.Providers;
import zendesk.chat.VisitorInfo;
import zendesk.core.JwtIdentity;
import zendesk.core.PushRegistrationProvider;
import zendesk.core.AnonymousIdentity;
import zendesk.core.Identity;
import zendesk.classic.messaging.MessagingActivity;
import zendesk.core.Zendesk;
import zendesk.support.CustomField;
import zendesk.support.Request;
import zendesk.support.RequestProvider;
import zendesk.support.RequestUpdates;
import zendesk.support.Support;
import zendesk.support.guide.HelpCenterActivity;
import zendesk.support.guide.ViewArticleActivity;
import zendesk.answerbot.AnswerBot;
import zendesk.answerbot.AnswerBotEngine;
import zendesk.support.SupportEngine;
import zendesk.support.request.RequestActivity;
import zendesk.support.requestlist.RequestListActivity;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;

@ReactModule(name = ReactNativeZendeskModule.NAME)
public class ReactNativeZendeskModule extends ReactContextBaseJavaModule implements ActivityEventListener {
  public static final String NAME = "ReactNativeZendesk";
  private ReactContext appContext;
  private static final String TAG = "ZendeskChat";
  private static final int MAX_TAGS_SIZE = 100;
  private final HashMap<String, CustomField> customFields;
  private final ArrayList<String> tags;
  // Contains the aggregate of all the logs sent by the app
  private StringBuffer log;
  private String logId;
  private RequestProvider requestProvider;

  @Nullable
  private Callback onOpenTicketDismiss;



  public ReactNativeZendeskModule(ReactApplicationContext reactContext) {
    super(reactContext);
    appContext = reactContext;
    customFields = new HashMap<>();
    log = new StringBuffer();
    tags = new ArrayList<>();

    onOpenTicketDismiss = null;
    reactContext.addActivityEventListener(this);
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void resetCustomFields() {
    customFields.clear();
  }

  @ReactMethod
  public void resetTags() {
    tags.clear();
  }

  @ReactMethod
  public void resetLog() {
    log.delete(0, log.length());
  }

  /* helper methods */
  private Boolean getBoolean(ReadableMap options, String key){
    if(options.hasKey(key)){
      return options.getBoolean(key);
    }
    return null;
  }

  private String getString(ReadableMap options, String key){
    if(options.hasKey(key)){
      return options.getString(key);
    }
    return null;
  }

  private final int INTENT_REQUEST_CODE = 100;

  @ReactMethod
  public void init(ReadableMap options) {
    String appId = options.getString("appId");
    String clientId = options.getString("clientId");
    String url = options.getString("url");
    String key = options.getString("key");
    this.logId = options.getString("logId");
    Context context = appContext;
    Zendesk.INSTANCE.init(context, url, appId, clientId);
    Support.INSTANCE.init(Zendesk.INSTANCE);
    AnswerBot.INSTANCE.init(Zendesk.INSTANCE, Support.INSTANCE);
    initChat(key);
  }

  private void checkIdentity(){
    Identity identity = Zendesk.INSTANCE.getIdentity();
  }

  @ReactMethod
  public void resetUserIdentity() {
    Chat.INSTANCE.resetIdentity();
  }

  @ReactMethod
  public void initChat(String key) {
    Context context = appContext;
    Chat.INSTANCE.init(context, key);
  }

  @ReactMethod
  public void setUserIdentity(ReadableMap options) {
    String token = getString(options,"token");
    if (token != null) {
      Identity identity = new JwtIdentity(token);
      Zendesk.INSTANCE.setIdentity(identity);
    } else {
      String name = getString(options,"name");
      String email = getString(options,"email");

      AnonymousIdentity.Builder builder = new AnonymousIdentity.Builder();

      if(name != null){
        builder.withNameIdentifier(name);
      }
      if(email != null){
        builder.withEmailIdentifier(email);
      }

      Identity identity = builder.build();
      Zendesk.INSTANCE.setIdentity(identity);
    }
    checkIdentity();
  }

  @ReactMethod
  public void addTicketCustomField(String key, String value){
    CustomField customField = new CustomField(Long.parseLong(key), value);
    this.customFields.put(key, customField);
  }

  @ReactMethod
  public void addTicketTag(String tag){
    tag = tag.replace(' ', '_');
    // avoid duplicates
    if(this.tags.contains(tag)){
      return;
    }
    // append to tail
    this.tags.add(tag);
    int elementsToRemove = this.tags.size() - MAX_TAGS_SIZE;
    int i = 0;
    while(i < elementsToRemove){
      // remove from head
      this.tags.remove(0);
      i++;
    }
  }

  @ReactMethod
  public void appendLog(String log){
    Integer logCapacity = 60000;

    this.log.insert(0, "\n"+log);
    this.log = new StringBuffer(this.log.substring(0, Math.max(0, Math.min(this.log.length()-1, logCapacity))));
  }

  @ReactMethod
  public void openTicket(String tickedId, Callback onClose){
    Activity activity = getCurrentActivity();

    onOpenTicketDismiss = onClose;

    if(this.logId != null) {
      // Add log custom field
      customFields.put(this.logId, new CustomField(Long.parseLong(this.logId), this.log.toString()));
    }

    // Open a ticket
    Intent requestActivityIntent = RequestActivity.builder()
      .withRequestId(tickedId)
      .withCustomFields(new ArrayList(customFields.values()))
      .withTags(this.tags)
      .intent(activity);

      activity.startActivityForResult(requestActivityIntent, INTENT_REQUEST_CODE);
  }

  @ReactMethod
public void hasOpenedTickets(final Promise promise) {
    requestProvider = Support.INSTANCE.provider().requestProvider();

    requestProvider.getAllRequests(new ZendeskCallback<List<Request>>() {
        @Override
        public void onSuccess(List<Request> requests) {
            WritableArray requestArray = Arguments.createArray();

            for (Request request : requests) {
                WritableMap requestMap = Arguments.createMap();

                // Extract request details
                requestMap.putString("id", request.getId());
                requestMap.putString("subject", request.getSubject());
                requestMap.putString("status", request.getStatus().name());
                requestMap.putDouble("requesterId", request.getRequesterId());

                if (request.getCreatedAt() != null) {
                    ZonedDateTime utcDateTime = request.getCreatedAt().toInstant().atZone(ZoneOffset.UTC);
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
                    requestMap.putString("createdAt", formatter.format(utcDateTime));
                }
                if (request.getUpdatedAt() != null) {
                    ZonedDateTime utcDateTime = request.getUpdatedAt().toInstant().atZone(ZoneOffset.UTC);
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
                    requestMap.putString("updatedAt", formatter.format(utcDateTime));
                }
                if (request.getDescription() != null) {
                    requestMap.putString("description", request.getDescription());
                }

                 if (request.getFirstComment() != null) {
                    WritableMap firstCommentMap = Arguments.createMap();
                    firstCommentMap.putString("id", request.getFirstComment().getId().toString());
                    firstCommentMap.putString("body", request.getFirstComment().getBody());
                    firstCommentMap.putString("authorId", request.getFirstComment().getAuthorId().toString());
                    firstCommentMap.putString("createdAt", request.getFirstComment().getCreatedAt().toString());
                    requestMap.putMap("firstComment", firstCommentMap);
                }

                // Last comment info
                if (request.getLastComment() != null) {
                    WritableMap lastCommentMap = Arguments.createMap();
                    lastCommentMap.putString("id", request.getLastComment().getId().toString());
                    lastCommentMap.putString("body", request.getLastComment().getBody());
                    lastCommentMap.putString("authorId", request.getLastComment().getAuthorId().toString());
                    lastCommentMap.putString("createdAt", request.getLastComment().getCreatedAt().toString());
                    requestMap.putMap("lastComment", lastCommentMap);
                }

                // Log for debugging
                Log.d(TAG, "Request Data: " + requestMap.toString());

                requestArray.pushMap(requestMap);
            }

            // Resolve promise with array of requests
            promise.resolve(requestArray);
        }

        @Override
        public void onError(ErrorResponse errorResponse) {
            promise.reject(errorResponse.getReason());
        }
    });
}

  @ReactMethod
public void getTotalNewResponses(final Promise promise) {
    requestProvider = Support.INSTANCE.provider().requestProvider();

    requestProvider.getUpdatesForDevice(new ZendeskCallback<RequestUpdates>() {
        @Override
        public void onSuccess(RequestUpdates requestUpdates) {
            int totalUpdates = requestUpdates.totalUpdates();
            Log.d("Zendesk", "Total Updates: " + totalUpdates);

            // Fetch all requests separately
            requestProvider.getAllRequests(new ZendeskCallback<List<Request>>() {
                @Override
                public void onSuccess(List<Request> requests) {
                    for (Request request : requests) {
                        Log.d("Zendesk", "Request ID: " + request.getId());
                        Log.d("Zendesk", "Subject: " + request.getSubject());
                        Log.d("Zendesk", "Status: " + request.getStatus());
                        Log.d("Zendesk", "Last Updated: " + request.getUpdatedAt());
                    }
                }

                @Override
                public void onError(ErrorResponse errorResponse) {
                    Log.e("Zendesk", "Error fetching requests: " + errorResponse.getReason());
                }
            });

            promise.resolve(totalUpdates);
        }

        @Override
        public void onError(ErrorResponse errorResponse) {
            Log.e("Zendesk", "Error fetching updates: " + errorResponse.getReason());
            promise.reject(errorResponse.getReason());
        }
    });
}

  @ReactMethod
  public void showTickets(Callback onClose){
    Activity activity = getCurrentActivity();
    onOpenTicketDismiss = onClose;

    // Show the user's tickets
    Intent requestActivityIntent = RequestListActivity.builder()
      .withContactUsButtonVisible(false)
      .intent(activity);

      activity.startActivityForResult(requestActivityIntent, INTENT_REQUEST_CODE);
  }

  @ReactMethod
  public void dismiss() {
    Activity activity = getCurrentActivity();
    if (activity != null) {
      activity.finishActivity(INTENT_REQUEST_CODE);
    }
  }


  @ReactMethod
  public void setNotificationToken(String token) {
    PushRegistrationProvider pushProvider = Zendesk.INSTANCE.provider().pushRegistrationProvider();
    if (pushProvider != null) {
      Log.d("ONESIGNALDEVICEID", "push provider is active");
      pushProvider.registerWithDeviceIdentifier(token, new ZendeskCallback<String>() {
    @Override
    public void onSuccess(String result) {
      Log.d("ONESIGNALDEVICEID", "success $result");
    }

    @Override
    public void onError(ErrorResponse errorResponse) {
      Log.d("ONESIGNALDEVICEID", "error $errorResponse");
    }
});
    }
  }

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
    if (requestCode == INTENT_REQUEST_CODE && onOpenTicketDismiss != null) {
        onOpenTicketDismiss.invoke();
        onOpenTicketDismiss = null;
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    Log.d(TAG, "onNewIntent");
  }
}
