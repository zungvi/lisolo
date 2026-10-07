package eu.siacs.conversations.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Parsing of the OpenAI-dialect responses the Zungvi AI endpoint returns. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ZungviAiTest {

    // Shape captured from a real ai.zungvi.net/v1/chat/completions response.
    private static final String REAL_RESPONSE =
            "{\"id\":\"d3f4cf2e\",\"object\":\"chat.completion\",\"model\":\"qwen2.5:7b-instruct\","
                + "\"session_id\":null,\"project\":{\"id\":\"54de2836\",\"name\":\"Lisolo Android"
                + " App\"},\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"I cannot come tomorrow.\"},\"finish_reason\":\"stop\"}]}";

    @Test
    public void readsTheAssistantMessage() {
        final var result = ZungviAi.parseCompletion(REAL_RESPONSE);

        assertTrue(result.isSuccess());
        assertEquals("I cannot come tomorrow.", result.text);
    }

    @Test
    public void trimsSurroundingWhitespace() {
        final var result = ZungviAi.parseCompletion(completionWithContent("  padded\n\n"));

        assertEquals("padded", result.text);
    }

    /** A 422 from the API carries its reason in "detail"; that is worth surfacing verbatim. */
    @Test
    public void surfacesTheApiErrorDetail() {
        final var result =
                ZungviAi.parseCompletion("{\"detail\":\"Input should be a valid string\"}");

        assertFalse(result.isSuccess());
        assertEquals("Input should be a valid string", result.errorMessage);
    }

    @Test
    public void emptyChoicesIsAFailureRatherThanEmptyText() {
        final var result = ZungviAi.parseCompletion("{\"choices\":[]}");

        assertFalse(result.isSuccess());
        assertFalse(result.errorMessage.isEmpty());
    }

    @Test
    public void emptyContentIsAFailure() {
        final var result = ZungviAi.parseCompletion(completionWithContent(""));

        assertFalse(result.isSuccess());
    }

    @Test
    public void malformedBodyDoesNotThrow() {
        final var result = ZungviAi.parseCompletion("<html>502 Bad Gateway</html>");

        assertFalse(result.isSuccess());
        assertFalse(result.errorMessage.isEmpty());
    }

    @Test
    public void payloadCarriesSystemAndUserTurnsAtTemperatureZero() throws Exception {
        final var payload =
                new JSONObject(ZungviAi.buildPayload("qwen2.5:7b-instruct", "be terse", "hello", 64));

        assertEquals("qwen2.5:7b-instruct", payload.getString("model"));
        assertEquals(0, payload.getInt("temperature"));
        assertEquals(64, payload.getInt("max_tokens"));
        final JSONArray messages = payload.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("be terse", messages.getJSONObject(0).getString("content"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        assertEquals("hello", messages.getJSONObject(1).getString("content"));
    }

    /**
     * The app asks for a size tier, not an Ollama tag. The server resolves it and a caller's tier
     * beats the model its API project pins, so this string is the contract between the two.
     */
    @Test
    public void defaultModelIsTheMediumTierRatherThanADeploymentSpecificTag() throws Exception {
        assertEquals("medium", ZungviAi.MODEL_MEDIUM);
        assertEquals(ZungviAi.MODEL_MEDIUM, ZungviAi.DEFAULT_MODEL);
        assertEquals("small", ZungviAi.MODEL_SMALL);
        assertEquals("large", ZungviAi.MODEL_LARGE);

        final var payload =
                new JSONObject(ZungviAi.buildPayload(ZungviAi.DEFAULT_MODEL, "be terse", "hi", 64));

        assertEquals("medium", payload.getString("model"));
    }

    @Test
    public void payloadOmitsAnEmptySystemTurn() throws Exception {
        final var payload = new JSONObject(ZungviAi.buildPayload("m", "", "hello", 64));

        final JSONArray messages = payload.getJSONArray("messages");
        assertEquals(1, messages.length());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
    }

    /**
     * The API rejects the multimodal content array with a 422, so message content has to stay a
     * plain string until a vision model exists.
     */
    @Test
    public void payloadSendsContentAsAPlainString() throws Exception {
        final var payload = new JSONObject(ZungviAi.buildPayload("m", "sys", "hi", 10));

        final Object content = payload.getJSONArray("messages").getJSONObject(1).get("content");
        assertTrue(content instanceof String);
    }

    private static String completionWithContent(final String content) {
        try {
            final var message = new JSONObject().put("role", "assistant").put("content", content);
            final var choices = new JSONArray().put(new JSONObject().put("message", message));
            return new JSONObject().put("choices", choices).toString();
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
}
