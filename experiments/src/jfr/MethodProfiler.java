package jfr;

import one.jfr.ClassRef;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.StackTrace;
import one.jfr.event.ExecutionSample;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MethodProfiler {
    public static final String WEBAPP_CLASS_LOADER_BASE_FIND_CLASS_INTERNAL = "org/apache/catalina/loader/WebappClassLoaderBase.findClassInternal(Ljava/lang/String;)Ljava/lang/Class";
    public static final String WEBAPP_CLASS_LOADER_BASE_LOAD_CLASS = "org/apache/catalina/loader/WebappClassLoaderBase.loadClass(Ljava/lang/String;)Ljava/lang/Class";
    private JfrReader jfr;
    private Set<String> includeThreads;
    private Map<String, LinkedList<String>> xxStackTraces = new HashMap<>();
    private Map<String, LinkedList<String>> xStackTraces = new HashMap<>();
    private Map<String, Integer> invocations = new HashMap<>();
    private Map<String, Period> timing = new HashMap<>();
    long minTime = Long.MAX_VALUE;
    long maxTime = Long.MIN_VALUE;
    private Set<String> skips = new HashSet<>();
    private int maxStackTraces = -1;
    private Set<String> springEnhancedMethods = new HashSet<>();
    private final List<String> focusMethodList;

    private int[] stackLengths = new int[50];

    private int webappCLloadClassCallsTotal;
    private int webappCLloadClassCallsL2;

    public MethodProfiler(JfrReader jfr) {
        this.jfr = jfr;

        includeThreads = new HashSet<>();

        includeThreads.add("localhost-startStop-1");
        includeThreads.add("OktaQuartzScheduler_QuartzSchedulerThread");
        //methods to get summaries for
        focusMethodList = new ArrayList<>();
        focusMethodList.add("okta/");
        focusMethodList.add("saasure/");
        focusMethodList.add("org/hibernate/tuple/entity/PojoEntityTuplizer.<init>");
        focusMethodList.add("org/springframework/beans/CachedIntrospectionResults.forClass");
        focusMethodList.add(WEBAPP_CLASS_LOADER_BASE_FIND_CLASS_INTERNAL);//5:39:15 or sample 4, 5, 6
        focusMethodList.add("org/springframework/context/annotation/ConfigurationClassEnhancer.createClass");
        focusMethodList.add("java/lang/Class.forName0");//look at hibernate BuildSessionFactory at 5:40:30
        focusMethodList.add("java/lang/ClassLoader.loadClass(Ljava/lang/String;)Ljava/lang/Class;");//look at 5:40:15 + 2 samples
        //focusMethodList.add("org/springframework/beans/factory/support/AbstractBeanFactory.getBean");
        focusMethodList.add(WEBAPP_CLASS_LOADER_BASE_LOAD_CLASS);
    }

    public boolean containsMethod(String method) {
        for (String m : focusMethodList) {
            if (method.contains(m))
                return true;
        }
        return false;
    }

    boolean includeSample(StackTrace trace) {
        MethodRef methodRef = jfr.methods.get(trace.methods[trace.methods.length - 1]);

        ClassRef cls = jfr.classes.get(methodRef.cls);

        byte[] className = jfr.symbols.get(cls.name);
        byte[] methodName = jfr.symbols.get(methodRef.name);

        String fqm = new String(className) + new String(methodName);

        if (!fqm.startsWith("java/lang/Thread")
                && !fqm.startsWith("java/util/TimerThread.run")
                && !fqm.startsWith("org/quartz/core/QuartzSchedulerThread.run")
                && !fqm.startsWith("org/")
                && !fqm.startsWith("java/lang/")
                && !fqm.startsWith("java/io/")
                && !fqm.startsWith("com/sun/")
                && !fqm.startsWith("java/security/")
                && !fqm.startsWith("sun/misc/")
                && !fqm.startsWith("java/util/")) {
            return false;
        }

        return true;
    }

    void parse() throws IOException {
        List<ExecutionSample> samples = jfr.readAllEvents(ExecutionSample.class);

        samples:
        for (int i = 0; i < samples.size(); i++) {
            ExecutionSample sample = samples.get(i);

            {
                minTime = Math.min(sample.time, minTime);
                maxTime = Math.max(sample.time, maxTime);
            }

            String thread = jfr.threads.get(sample.tid);

            if (!includeThreads.contains(thread)) {
                continue;
            }

            StackTrace trace = jfr.stackTraces.get(sample.stackTraceId);

            stackLengths[trace.methods.length / 100]++;

            maxStackTraces = Math.max(trace.methods.length, maxStackTraces);

            if (!includeSample(trace)) {
                continue;
            }

            final LinkedList<String> stackTrace = buildStackTrace(thread, trace);

            boolean isOkta = false;

            for (String frame : stackTrace) {
                isOkta = containsMethod(frame);

                if (isOkta) {
                    break;
                }
            }

            if (isOkta) {
                final List<String> xStackTrace = xStackTraces.get(thread);

                boolean isInWebappCLloadClass = false;

                for (int j = 0; j < stackTrace.size(); j++) {
                    String frame = stackTrace.get(j);

                    if (!containsMethod(frame)) {
                        continue;
                    }

                    if (isInWebappCLloadClass && frame.contains(WEBAPP_CLASS_LOADER_BASE_LOAD_CLASS)) {
                        webappCLloadClassCallsTotal++;
                        webappCLloadClassCallsL2++;

                        continue;
                    }

                    if (frame.contains(WEBAPP_CLASS_LOADER_BASE_LOAD_CLASS)) {
                        isInWebappCLloadClass = true;
                        webappCLloadClassCallsTotal++;
                    }

                    final boolean isNewInvocation = xStackTrace == null ||
                            xStackTrace.size() - 1 < j ||
                            !xStackTrace.get(j).equals(frame);

                    if (isNewInvocation) {
                        invocations.compute(frame, (k, v) -> v == null ? 0 : ++v);
                    }

                    final int invocationNumber = invocations.get(frame);

                    final String invokedFrame = invocationNumber + ":" + frame;

                    timing.merge(invokedFrame, new Period(sample.time, sample.time), (xVal, nVal) -> {
                        if (xVal != null) {
                            nVal.startTime = xVal.startTime;
                        }

                        return nVal;
                    });
                }
            }

            //debug help
            if (xStackTraces.get(thread) != null && !xStackTraces.get(thread).getLast().endsWith("/libsystem_kernel.dylib.__psynch_cvwait")) {
                xxStackTraces.put(thread, xStackTraces.get(thread));
            }

            xStackTraces.put(thread, stackTrace);
        }

        ArrayList<Map.Entry<String, Period>> configs = new ArrayList<>(timing.entrySet());

        configs.sort(Collections.reverseOrder((o1, o2) -> {
            long d1 = o1.getValue().elapsedTime();
            long d2 = o2.getValue().elapsedTime();

            return Long.compare(d1, d2);
        }));

        Map<String, Long> sums = new HashMap<>();

        int webappCLloadClassZeroDuration = 0;
        for (int i = 0; i < configs.size(); i++) {
            Map.Entry<String, Period> config = configs.get(i);

            final long duration = config.getValue().elapsedTime();

            if (duration == 0 && config.getKey().contains(WEBAPP_CLASS_LOADER_BASE_LOAD_CLASS)) {
                webappCLloadClassZeroDuration++;
            }

            //
            String method = config.getKey();
            method = method.substring(method.indexOf('/'));

            sums.merge(method, duration, (x, y) -> {
                if (x != null) {
                    y = y + x;
                }
                return y;
            });

            System.out.println(config.getKey() + " -> " + duration / 1000000);
        }

        //
        System.out.println("skips ");
        for (String skip : skips) {
            System.out.println("MethodProfiler.skip: " + skip);
        }
        //
        System.out.println("Method total time");
        List<Map.Entry<String, Long>> sumsList = sums.entrySet().stream().sorted(Comparator.comparingLong(Map.Entry::getValue)).collect(Collectors.toList());
        for (Map.Entry<String, Long> entry : sumsList) {
            System.out.println(entry.getKey() + " -> " + entry.getValue() / 1000000);
        }
        //
        System.out.println("Spring enhanced methods " + springEnhancedMethods.size());
        for (Iterator<String> it = springEnhancedMethods.iterator(); it.hasNext(); ) {
            String method = it.next();
            //System.out.println(method);
        }
        //
        System.out.println("stack-len\tcount");
        for (int i = 1; i < stackLengths.length; i++) {
            int stackLength = i * 100;
            System.out.println(stackLength + "\t" + stackLengths[i]);
        }
        //
        System.out.printf("WebappClassLoader.loadClass calls total / level2: (%d / %d)\n", webappCLloadClassCallsTotal, webappCLloadClassCallsL2);
        System.out.printf("WebappClassLoader.zeroDurationCount %d\n", webappCLloadClassZeroDuration);
        //
        System.out.printf("timing table.size %d\n", this.timing.size());
    }

    private LinkedList<String> buildStackTrace(String thread, StackTrace trace) {
        LinkedList<String> tqStackTrace = new LinkedList<>();

        for (long methodId : trace.methods) {
            MethodRef methodRef = jfr.methods.get(methodId);

            ClassRef cls = jfr.classes.get(methodRef.cls);

            byte[] className = jfr.symbols.get(cls.name);
            byte[] methodName = jfr.symbols.get(methodRef.name);
            byte[] signature = jfr.symbols.get(methodRef.sig);

            final String method = new String(className) + '.' + new String(methodName) + new String(signature);

            if (method.contains("$$EnhancerBySpringCGLIB")) {
                springEnhancedMethods.add(method);
                continue;
            }

            final String fqMethodName = thread + '/' + method;

            tqStackTrace.addFirst(fqMethodName);
        }

        return tqStackTrace;
    }

    class Period {
        long startTime;
        long endTime;

        public Period(long startTime, long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }

        long elapsedTime() {
            return endTime - startTime;
        }
    }

    public static void main(String[] args) throws IOException {
        JfrReader jfr = new JfrReader(args[args.length - 1]);

        MethodProfiler profiler = new MethodProfiler(jfr);

        profiler.parse();
    }
}
