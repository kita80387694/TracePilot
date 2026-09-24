package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

public interface ModelGateway {
  boolean configured();

  default java.util.Map<String,Object> configuration() { return java.util.Map.of("adapterVersion", "test-double"); }

  ModelReply call(String system, String user);
  default ModelReply call(String system,String user,String taskId,int ordinal){return call(system,user);}
  default ModelReply call(String system,String user,String taskId,int ordinal,java.util.function.Consumer<java.util.Map<String,Object>> observer){return call(system,user,taskId,ordinal);}
}
