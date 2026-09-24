package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import java.util.Map;
import java.util.function.Consumer;

/** Explicit phase capabilities; never infer permissions by parsing prompt text. */
interface ActionModelGateway extends ModelGateway {
 String actionPrompt(boolean reportOnly,boolean queryAllowed);
 String actionPromptVersion();
 ModelReply callAction(boolean reportOnly,boolean queryAllowed,String input,String taskId,int ordinal,Consumer<Map<String,Object>> observer);
}
