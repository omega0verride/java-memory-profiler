package com.toro.al.memprofiler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface method as a profiled scope. When the interface is wrapped
 * with {@link MemoryProfiler#wrap(Class, Object)}, each call records a
 * {@code SCOPE_START} / {@code SCOPE_END} pair with heap delta and duration.
 *
 * <pre>
 *   public interface Job {
 *     @Profile("parse-feed")           void parse();   // cheap scope
 *     @Profile(value="load", gc=true)  void load();    // scopeGc: GC at both ends
 *   }
 *   Job j = MemoryProfiler.wrap(Job.class, new JobImpl());
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Profile {
  /** Label shown in the UI. Defaults to {@code ClassName.methodName} if empty. */
  String value() default "";

  /**
   * If {@code true}, uses {@link MemoryProfiler#scopeGc(String)} (forces GC at
   * entry and exit for a precise delta). Defaults to {@code false} (cheap).
   */
  boolean gc() default false;
}
