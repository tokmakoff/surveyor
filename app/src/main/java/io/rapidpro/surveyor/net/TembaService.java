package io.rapidpro.surveyor.net;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.TelephonyManager;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.rapidpro.flows.runner.Contact;
import io.rapidpro.flows.runner.Field;
import io.rapidpro.surveyor.R;
import io.rapidpro.surveyor.Surveyor;
import io.rapidpro.surveyor.TembaException;
import io.rapidpro.surveyor.data.DBFlow;
import io.rapidpro.surveyor.data.DBLocation;
import io.rapidpro.surveyor.data.DBOrg;
import io.rapidpro.surveyor.data.Submission;
import io.realm.Realm;
import io.realm.RealmObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class TembaService {

    private TembaAPI m_api;
    private Retrofit m_retrofit;
    private String m_token;
    private FlowList m_flowList;

    public TembaService(String host) {
        m_api = getAPIAccessor(host);
    }

    public FlowList getLastFlows() { return m_flowList; }

    public void setToken(String token) {
        m_token = "Token " + token;
    }

    public String getToken() {
        return m_token;
    }

    public void getOrgs(String email, String password, Callback<List<DBOrg>> callback) {
        m_api.getOrgs(email, password, "S").enqueue(callback);
    }

    public DBOrg getOrg() {
        try {
            return m_api.getOrg(getToken()).execute().body();
        } catch (IOException e) {
            throw new TembaException(e);
        }
    }

    public void getFlows(final Callback<FlowList> callback) {
        m_api.getFlows(getToken(), "S", false).enqueue(new Callback<FlowList>() {
            @Override
            public void onResponse(Call<FlowList> call, Response<FlowList> response) {
                m_flowList = response.body();
                callback.onResponse(call, response);
            }

            @Override
            public void onFailure(Call<FlowList> call, Throwable t) {
                callback.onFailure(call, t);
            }
        });
    }


    /**
     * Get the flow definition, or null if it fails
     */
    public Definitions getLegacyFlowDefinition(String flowUuid) {
        try {
            Response<FlowDefinition> flowDefinitionResponse = m_api.getLegacyFlowDefinition(getToken(), flowUuid).execute();
            FlowDefinition def = flowDefinitionResponse.body();
            Definitions definitions = new Definitions();

            if (def == null) {
                  throw new IOException("Encountered empty flow definition");
            }
            definitions.version = def.version;
            definitions.flows = new ArrayList<>();
            definitions.flows.add(def);
            return definitions;
        } catch (IOException e) {
            Surveyor.LOG.e("Error fetching flow definition", e);
        }
        return null;
    }

    public void getFlowDefinition(final DBFlow flow, final Callback<Definitions> callback) {

        final Realm realm = Realm.getDefaultInstance();
        realm.beginTransaction();
        flow.setFetching(true);
        realm.commitTransaction();

        m_api.getFlowDefinition(getToken(), flow.getUuid()).enqueue(new Callback<Definitions>() {
            @Override
            public void onResponse(final Call<Definitions> call, final Response<Definitions> response) {
                realm.beginTransaction();
                flow.setFetching(false);
                realm.commitTransaction();
                realm.close();
                callback.onResponse(call, response);
            }

            @Override
            public void onFailure(Call<Definitions> call, Throwable t) {
                realm.beginTransaction();
                flow.setFetching(false);
                realm.commitTransaction();
                realm.close();
                callback.onFailure(call, t);
            }
        });
    }

    public Contact addContact(final Contact contact) {

        if ("base".equals(contact.getLanguage())) {
            contact.setLanguage(null);
        }

        try {
            JsonObject result = m_api.addContact(getToken(), contact.toJson()).execute().body();
            String uuid = result.get("uuid").getAsString();
            contact.setUuid(uuid);
            return contact;
        } catch (IOException e) {
            throw new TembaException(e);
        }
    }

    /**
     * Uploads a media file and returns the remove URL
     * @param file the local file to upload
     * @return the relative path to media
     */
    public String uploadMedia(File file, String extension) {

        Map<String, RequestBody> map = new HashMap<>();

        RequestBody fileBody = RequestBody.create(MediaType.parse("multipart/form-data"), file);
        map.put("media_file\"; filename=\"" + file.getName(), fileBody);

        RequestBody extBody = RequestBody.create(MediaType.parse("text/plain"), extension);
        map.put("extension", extBody);

        try {
            JsonObject result = m_api.uploadMedia(getToken(), map).execute().body();
            return result.get("location").getAsString();
        } catch (IOException e) {
            throw new TembaException(e);
        }
    }

    public void addResults(final Submission submission) {
        try {

            boolean success = false;
            for (JsonObject result : submission.getResultsJson()) {
                Response response = m_api.addResults(getToken(), result).execute();
                if (response.isSuccessful()) {
                    success = response.isSuccessful();
                } else {
                    Surveyor.LOG.d("Response was not successful: " + response.toString());
                }

            }
            if (success) {
                submission.delete();
            } else {
                Surveyor.LOG.d("Error submitting results");
                throw new TembaException("Error submitting results");
            }
        } catch (IOException e) {
            throw new TembaException(e);
        }
    }


    private String getIndexOfLastValidInput(JsonArray steps) {
        int lastErrorIndex = 0;
        String lastValidInput = null;
        int stepCount = 0;
        for(final JsonElement stepElement : steps) {

            JsonObject step = stepElement.getAsJsonObject();
            JsonElement ruleElement = step.get("rule");
            if (ruleElement.isJsonNull()) {

                JsonArray actions = step.get("actions").getAsJsonArray();
                JsonElement msg = actions.get(0).getAsJsonObject().get("msg");

                if (msg.toString().contains("Error") || msg.toString().contains("Erro")) {
                    Surveyor.LOG.d("Detected error at:" + stepCount);
                    lastErrorIndex = stepCount;
                }
            } else {
                JsonObject rule = step.get("rule").getAsJsonObject();
                JsonElement value = rule.get("value");
                if (stepCount == lastErrorIndex + 1) {
                    lastValidInput = value.getAsString();
                }
            }
            stepCount++;
        }
        return (lastValidInput);
    }

    public void addResultsViaSMS(final Submission submission) {

        ArrayList<String> enteredData = new ArrayList<String>();

        Surveyor.LOG.d("addResultsViaSMS called with:" + submission.toString());
        JsonElement result = submission.toJson();
        Surveyor.LOG.d("result is:" + result.toString());
        //JsonArray steps = result.getAsJsonArray("steps");
        JsonObject o = result.getAsJsonObject();
        Surveyor.LOG.d("o is:" + o.toString());
        JsonArray steps = (JsonArray) o.get("steps");
        Surveyor.LOG.d("steps is:" + steps.toString());

        String lastValidInput = getIndexOfLastValidInput(steps);

        /*
        for(final JsonElement stepElement : steps) {
            JsonObject step = stepElement.getAsJsonObject();
            JsonElement ruleElement = step.get("rule");
            if (!ruleElement.isJsonNull()) {
                JsonObject rule = step.get("rule").getAsJsonObject();
                JsonElement value = rule.get("value");

                if (value.toString().indexOf(" ") == -1) {
                    JsonElement category = rule.get("category");

                    Surveyor.LOG.d("step[rule] is:" + rule.toString());
                    Surveyor.LOG.d("step[rule].category is:" + category);
                    Surveyor.LOG.d("step[rule].value is:" + value);

                    enteredData.add(value.toString());
                }
            }
        }
        */

//        Uri smsUri = Uri.parse("tel:123456");
//        Intent intent = new Intent(Intent.ACTION_VIEW, smsUri);
//        intent.putExtra("sms_body", "sms text");
//        intent.setType("vnd.android-dir/mms-sms");
//        Surveyor.get().startActivity(intent);

        String smsText = ""; // "MCRAPE ";
        /*
        for (String collectedText : enteredData ) {
            smsText += collectedText + " ";
        }
        */
        smsText += lastValidInput;

        String numberToSendSMSTo = getSMSFromPhoneNumber();

        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.putExtra("sms_body", smsText);
        intent.setData(Uri.parse("smsto:" + Uri.encode(numberToSendSMSTo)));
        Surveyor.get().startActivity(intent);

        submission.delete();

        /*
        try {

            boolean success = false;
            for (JsonObject result : submission.getResultsJson()) {
                Response response = m_api.addResults(getToken(), result).execute();
                if (response.isSuccessful()) {
                    success = response.isSuccessful();
                }

            }
            if (success) {
                submission.delete();
            } else {
                throw new TembaException("Error submitting results");
            }
        } catch (IOException e) {
            throw new TembaException(e);
        }
        */
    }

    static final String AU_OPTUS = "+61 435995771";
    static final String MOZ_VOD = "+258 849587439";
    static final String MOZ_MCEL = "+258 823196863";
    static final String MOZ_MOV = "+258 875163545";


    static final String MOZ_MCEL_OPERATOR = "64301";
    static final String MOZ_MOV_OPERATOR = "64303";
    static final String MOZ_VOD_OPERATOR = "64304";


    static final String NL_KPN_OPERATOR = "20408";

    static final String AU_OPTUS_OPERATOR1 = "50590";
    static final String AU_OPTUS_OPERATOR2 = "50502";

    private String getSMSFromPhoneNumber() {


        TelephonyManager phoneManager = (TelephonyManager)
                Surveyor.get().getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);

        String operator = null;
        operator = phoneManager.getNetworkOperator();
        Surveyor.LOG.d("operator = " + operator);

        String phoneNumber = null;

        Surveyor.LOG.d("operator is: " + operator);
        // Parse the number and determine which of the three endpoints it should use
        if (operator.equals(AU_OPTUS_OPERATOR1) || operator.equals(AU_OPTUS_OPERATOR2)) {
            return(AU_OPTUS);
        } else if (operator.equals(MOZ_VOD_OPERATOR)) {
            // Vodacom
            return(MOZ_VOD);
        } else if (operator.equals(MOZ_MCEL_OPERATOR)) {
            // mCel
            return(MOZ_MCEL);
        } else if (operator.equals(MOZ_MOV_OPERATOR)) {
            // Movitel
            return (MOZ_MOV);
        } else if (operator.equals(NL_KPN_OPERATOR)) {
            return ("Dutch endpoint");
        } else
            return("Error: no number mapping detected for your phone's operator (" + operator + ")");
    }


    public void addCreatedFields(HashMap<String, Field> fields) {
        for (Field field : fields.values()) {
            m_api.addCreatedField(getToken(), field);
        }
    }

    public List<DBLocation> getLocations() {

        List<DBLocation> locations = new ArrayList<>();

        try {
            int pageNumber = 1;
            // fetch our first page
            LocationResultPage page = m_api.getLocationPage(getToken(), true, pageNumber).execute().body();
            locations.addAll(page.results);

            // fetch subsequent pages until we are done
            while (page != null && page.next != null && page.next.trim().length() != 0) {
                page = m_api.getLocationPage(getToken(), true, ++pageNumber).execute().body();
                locations.addAll(page.results);
            }

            return locations;
        } catch (IOException e) {
            throw new TembaException(e);
        }
    }

    public List<Field> getFields() {

        try {
            List<Field> fields = new ArrayList<>();
            int pageNumber = 1;
            FieldResultPage page = m_api.getFieldPage(getToken(), pageNumber).execute().body();
            fields.addAll(page.getRunnerFields());

            while (page != null && page.next != null && page.next.trim().length() != 0) {
                page = m_api.getFieldPage(getToken(), ++pageNumber).execute().body();
                fields.addAll(page.getRunnerFields());
            }
            return fields;
        } catch (IOException e) {
            throw new TembaException(e);
        }
    }

    private TembaAPI getAPIAccessor(String host) {

        Gson gson = new GsonBuilder().setExclusionStrategies(new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes f) {
                return f.getDeclaringClass().equals(RealmObject.class);
            }

            @Override
            public boolean shouldSkipClass(Class<?> clazz) {
                return false;
            }
        }).registerTypeAdapterFactory(new FlowListTypeAdapterFactory()).create();

        final OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .build();

        try {
            m_retrofit = new Retrofit.Builder()
                    .baseUrl(host)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .client(okHttpClient)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new TembaException(e);
        }

        // .setLogLevel(RestAdapter.LogLevel.FULL)
        return m_retrofit.create(TembaAPI.class);
    }

    public APIError parseError(Response<?> response){
        Converter<ResponseBody, APIError> converter =
                m_retrofit.responseBodyConverter(APIError.class, new Annotation[0]);

        APIError error = new APIError(response.code(), null);
        try {
            error = converter.convert(response.errorBody());
        } catch (IOException e) {
            try {
                error = new APIError(response.code(), response.errorBody().string());
            } catch (IOException last) {}
        }

        return error;
    }

    public int getErrorMessage(Throwable t) {

        if (t == null) {
            return R.string.error_server_not_found;
        }

        return R.string.error_server_failure;
    }


    private class FlowListTypeAdapterFactory extends CustomizedTypeAdapterFactory<FlowList> {
        private FlowListTypeAdapterFactory() {
            super(FlowList.class);
        }

        @Override protected void beforeWrite(FlowList flow, JsonElement json) {}

        @Override protected void afterRead(JsonElement deserialized) {
            JsonObject custom = deserialized.getAsJsonObject();
            JsonArray flows = custom.get("results").getAsJsonArray();
            for (int i=0; i<flows.size(); i++) {
                int questionCount = 0;
                JsonObject flow = flows.get(i).getAsJsonObject();
                JsonArray rulesets = flow.get("rulesets").getAsJsonArray();
                for (int j=0; j<rulesets.size(); j++) {
                    String rulesetType = rulesets.get(j).getAsJsonObject().get("ruleset_type").getAsString();
                    if (rulesetType != null && rulesetType.startsWith("wait_")) {
                        questionCount++;
                    }
                }

                flow.add("questionCount", new JsonPrimitive(questionCount));
                if (questionCount == 0) {
                    flows.remove(i);
                    i--;
                }
            }
        }
    }
}
