/*
 * Copyright (c) 2016 Mockito contributors
 * This program is made available under the terms of the MIT License.
 */
package org.mockito.internal.creation.bytebuddy;

import org.mockito.MockedConstruction;
import org.mockito.creation.instance.InstantiationException;
import org.mockito.creation.instance.Instantiator;
import org.mockito.exceptions.base.MockitoException;
import org.mockito.exceptions.base.MockitoInitializationException;
import org.mockito.exceptions.misusing.MockitoConfigurationException;
import org.mockito.internal.PremainAttachAccess;
import org.mockito.internal.SuppressSignatureCheck;
import org.mockito.internal.configuration.plugins.Plugins;
import org.mockito.internal.creation.bytebuddy.access.MockAccess;
import org.mockito.internal.creation.bytebuddy.access.MockMethodInterceptor;
import org.mockito.internal.creation.instance.ConstructorInstantiator;
import org.mockito.internal.framework.DisabledMockHandler;
import org.mockito.internal.util.Platform;
import org.mockito.internal.util.concurrent.DetachedThreadLocal;
import org.mockito.internal.util.concurrent.WeakConcurrentMap;
import org.mockito.invocation.MockHandler;
import org.mockito.mock.MockCreationSettings;
import org.mockito.plugins.InlineMockMaker;
import org.mockito.plugins.MemberAccessor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.mockito.internal.creation.bytebuddy.InlineBytecodeGenerator.EXCLUDES;
import static org.mockito.internal.util.StringUtil.join;

/**
 * Agent and subclass based mock maker.
 * <p>
 * This mock maker uses a combination of the Java instrumentation API and sub-classing rather than creating
 * a new sub-class to create a mock. This way, it becomes possible to mock final types and methods. This mock
 * maker <strong>must be activated explicitly</strong> for supporting mocking final types and methods:
 * <p>
 * <p>
 * This mock maker can be activated by creating the file <code>/mockito-extensions/org.mockito.plugins.MockMaker</code>
 * containing the text <code>mock-maker-inline</code> or <code>org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker</code>.
 * <p>
 * <p>
 * This mock maker will make a best effort to avoid subclass creation when creating a mock. Otherwise it will use the
 * <code>org.mockito.internal.creation.bytebuddy.SubclassByteBuddyMockMaker</code> to create the mock class. That means
 * that the following condition is true
 * <p>
 * <pre class="code"><code class="java">
 * class Foo { }
 * assert mock(Foo.class).getClass() == Foo.class;
 * </pre></code>
 * <p>
 * unless any of the following conditions is met, in such case the mock maker <em>falls back</em> to
 * the creation of a subclass.
 * <p>
 * <ul>
 * <li>the type to mock is an abstract class.</li>
 * <li>the mock is set to require additional interfaces.</li>
 * <li>the mock is <a href="#20">explicitly set to support serialization</a>.</li>
 * </ul>
 * <p>
 * <p>
 * Some type of the JDK cannot be mocked, this includes <code>Class</code>, <code>String</code>, and wrapper types.
 * <p>
 * <p>
 * Nevertheless, final methods of such types are mocked when using the inlining mock maker. Mocking final types and enums
 * does however remain impossible when explicitly requiring serialization support or when adding ancillary interfaces.
 * <p>
 * <p>
 * Important behavioral changes when using inline-mocks:
 * <ul>
 * <li>Mockito is capable of mocking package-private methods even if they are defined in different packages than
 * the mocked type. Mockito voluntarily never mocks package-visible methods within <code>java.*</code> packages.</li>
 * <li>Additionally to final types, Mockito can now mock types that are not visible for extension; such types
 * include private types in a protected package.</li>
 * <li>Mockito can no longer mock <code>native</code> methods. Inline mocks require byte code manipulation of a
 * method where native methods do not offer any byte code to manipulate.</li>
 * <li>Mockito can no longer strip <code>synchronized</code> modifiers from mocked instances.</li>
 * </ul>
 * <p>
 * <p>
 * Note that inline mocks require a Java agent to be attached. Mockito will attempt an attachment of a Java agent upon
 * loading the mock maker for creating inline mocks. Such runtime attachment is only possible when using a JVM that
 * is part of a JDK or when using a Java 9 VM. When running on a non-JDK VM prior to Java 9, it is however possible to
 * manually add the <a href="https://bytebuddy.net">Byte Buddy Java agent jar</a> using the <code>-javaagent</code>
 * parameter upon starting the JVM. Furthermore, the inlining mock maker requires the VM to support class retransformation
 * (also known as HotSwap). All major VM distributions such as HotSpot (OpenJDK), J9 (IBM/Websphere) or Zing (Azul)
 * support this feature.
 */
@SuppressSignatureCheck
class InlineDelegateByteBuddyMockMaker
        implements ClassCreatingMockMaker, InlineMockMaker, Instantiator {

    private static final Instrumentation INSTRUMENTATION;

    private static final Throwable INITIALIZATION_ERROR;

    static {
        Instrumentation instrumentation;
        Throwable initializationError = null;

        // ByteBuddy internally may attempt to fork a subprocess. In Java 11 and Java 19, the
        // Java process class observes the os.name system property to determine the OS and
        // thus determine how to fork a new process. If the user is stubbing System
        // properties, they may clear the existing System properties, which will cause this
        // to fail. This is very much an implementation detail, but it will result in Mockito
        // failing to load with an error that is not overly clear, so let's attempt to detect
        // this issue ahead of time instead.
        if (System.getProperty("os.name") == null) {
            throw new IllegalStateException(
                    join(
                            "The Byte Buddy agent cannot be loaded.",
                            "",
                            "To initialise the Byte Buddy agent, a subprocess may need to be created. To do this, the JVM requires "
                                    + "knowledge of the 'os.name' System property in most JRE implementations. This property is not present, "
                                    + "which means this operation will fail to complete. Please first make sure you are not clearing this "
                                    + "property anywhere, and failing that, raise a bug with your JVM vendor."));
        }

        try {
            try {
                instrumentation = PremainAttachAccess.getInstrumentation();
                File boot = File.createTempFile("mockitoboot", ".jar");
                boot.deleteOnExit();
                try (JarOutputStream outputStream =
                        new JarOutputStream(new FileOutputStream(boot))) {
                    InputStream inputStream =
                            InlineDelegateByteBuddyMockMaker.class.getResourceAsStream(
                                    "inject-MockMethodDispatcher.raw");
                    if (inputStream == null) {
                        throw new IllegalStateException(
                                join(
                                        "The MockMethodDispatcher class file is not locatable: "
                                                + "inject-MockMethodDispatcher.raw"
                                                + " in context of "
                                                + InlineDelegateByteBuddyMockMaker.class.getName(),
                                        "",
                                        "The class loader responsible for looking up the resource: "
                                                + InlineDelegateByteBuddyMockMaker.class
                                                        .getClassLoader(),
                                        "",
                                        "The module responsible for looking up the resource: "
                                                + InlineDelegateByteBuddyMockMaker.class
                                                        .getModule()));
                    }
                    try (inputStream) {
                        outputStream.putNextEntry(
                                new JarEntry(
                                        "org/mockito/internal/creation/bytebuddy/inject/MockMethodDispatcher.class"));
                        int length;
                        byte[] buffer = new byte[1024];
                        while ((length = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, length);
                        }
                    }
                    outputStream.closeEntry();
                }
                try (JarFile jarfile = new JarFile(boot)) {
                    instrumentation.appendToBootstrapClassLoaderSearch(jarfile);
                }
                Class<?> dispatcher;
                try {
                    dispatcher =
                            Class.forName(
                                    "org.mockito.internal.creation.bytebuddy.inject.MockMethodDispatcher",
                                    false,
                                    null);
                } catch (ClassNotFoundException cnfe) {
                    throw new IllegalStateException(
                            join(
                                    "Mockito failed to inject the MockMethodDispatcher class into the bootstrap class loader",
                                    "",
                                    "It seems like your current VM does not support the instrumentation API correctly."),
                            cnfe);
                }
                try {
                    InlineDelegateByteBuddyMockMaker.class
                            .getModule()
                            .addReads(dispatcher.getModule());
                } catch (Exception e) {
                    throw new IllegalStateException(
                            join(
                                    "Mockito failed to adjust the module graph to read the dispatcher module",
                                    "",
                                    "Dispatcher: "
                                            + dispatcher
                                            + " is loaded by "
                                            + dispatcher.getClassLoader()),
                            e);
                }
            } catch (IOException ioe) {
                throw new IllegalStateException(
                        join(
                                "Mockito could not self-attach a Java agent to the current VM. This feature is required for inline mocking.",
                                "This error occured due to an I/O error during the creation of this agent: "
                                        + ioe,
                                "",
                                "Potentially, the current VM does not support the instrumentation API correctly"),
                        ioe);
            }
        } catch (Throwable throwable) {
            instrumentation = null;
            initializationError = throwable;
        }
        INSTRUMENTATION = instrumentation;
        INITIALIZATION_ERROR = initializationError;
    }

    private final BytecodeGenerator bytecodeGenerator;

    private final WeakConcurrentMap<Object, MockMethodInterceptor> mocks =
            new WeakConcurrentMap<>(false);

    private final DetachedThreadLocal<Map<Class<?>, MockMethodInterceptor>> mockedStatics =
            new DetachedThreadLocal<>(DetachedThreadLocal.Cleaner.MANUAL);

    private final DetachedThreadLocal<Map<Class<?>, BiConsumer<Object, MockedConstruction.Context>>>
            mockedConstruction = new DetachedThreadLocal<>(DetachedThreadLocal.Cleaner.MANUAL);

    private final ThreadLocal<Class<?>> currentMocking = ThreadLocal.withInitial(() -> null);

    private final ThreadLocal<Object> currentSpied = new ThreadLocal<>();

    InlineDelegateByteBuddyMockMaker() {
        if (INITIALIZATION_ERROR != null) {
            String detail;
            if (PlatformUtils.isAndroidPlatform() || PlatformUtils.isProbablyTermuxEnvironment()) {
                detail =
                        "It appears as if you are trying to run this mock maker on Android which does not support the instrumentation API.";
            } else {
                try {
                    if (INITIALIZATION_ERROR instanceof NoClassDefFoundError
                            && INITIALIZATION_ERROR.getMessage() != null
                            && INITIALIZATION_ERROR
                                    .getMessage()
                                    .startsWith("net/bytebuddy/agent/")) {
                        detail =
                                join(
                                        "It seems like you are running Mockito with an incomplete or inconsistent class path. Byte Buddy Agent could not be loaded.",
                                        "",
                                        "Byte Buddy Agent is available on Maven Central as 'net.bytebuddy:byte-buddy-agent' with the module name 'net.bytebuddy.agent'.",
                                        "Normally, your IDE or build tool (such as Maven or Gradle) should take care of your class path completion but ");
                    } else if (Class.forName("javax.tools.ToolProvider")
                                    .getMethod("getSystemJavaCompiler")
                                    .invoke(null)
                            == null) {
                        detail =
                                "It appears as if you are running on a JRE. Either install a JDK or add JNA to the class path.";
                    } else {
                        detail =
                                "It appears as if your JDK does not supply a working agent attachment mechanism.";
                    }
                } catch (Throwable ignored) {
                    detail =
                            "It appears as if you are running an incomplete JVM installation that might not support all tooling APIs";
                }
            }
            throw new MockitoInitializationException(
                    join(
                            "Could not initialize inline Byte Buddy mock maker.",
                            "",
                            detail,
                            Platform.describe()),
                    INITIALIZATION_ERROR);
        }

        ThreadLocal<Class<?>> currentConstruction = new ThreadLocal<>();
        ThreadLocal<Boolean> isSuspended = ThreadLocal.withInitial(() -> false);
        Predicate<Class<?>> isCallFromSubclassConstructor = StackWalkerChecker.orFallback();
        Predicate<Class<?>> isMockConstruction =
                type -> {
                    if (isSuspended.get()) {
                        return false;
                    } else if ((currentMocking.get() != null
                                    && type.isAssignableFrom(currentMocking.get()))
                            || currentConstruction.get() != null) {
                        return true;
                    }
                    Map<Class<?>, ?> interceptors = mockedConstruction.get();
                    if (interceptors != null && interceptors.containsKey(type)) {
                        // We only initiate a construction mock, if the call originates from an
                        // un-mocked (as suppression is not enabled) subclass constructor.
                        if (isCallFromSubclassConstructor.test(type)) {
                            return false;
                        }
                        currentConstruction.set(type);
                        return true;
                    } else {
                        return false;
                    }
                };
        ConstructionCallback onConstruction =
                (type, object, arguments, parameterTypeNames) -> {
                    if (currentMocking.get() != null) {
                        Object spy = currentSpied.get();
                        if (spy == null) {
                            return null;
                        } else if (type.isInstance(spy)) {
                            return spy;
                        } else {
                            isSuspended.set(true);
                            try {
                                // Unexpected construction of non-spied object
                                throw new MockitoException(
                                        "Unexpected spy for "
                                                + type.getName()
                                                + " on instance of "
                                                + object.getClass().getName(),
                                        object instanceof Throwable ? (Throwable) object : null);
                            } finally {
                                isSuspended.set(false);
                            }
                        }
                    } else if (currentConstruction.get() != type) {
                        return null;
                    }
                    currentConstruction.remove();
                    isSuspended.set(true);
                    try {
                        Map<Class<?>, BiConsumer<Object, MockedConstruction.Context>> interceptors =
                                mockedConstruction.get();
                        if (interceptors != null) {
                            BiConsumer<Object, MockedConstruction.Context> interceptor =
                                    interceptors.get(type);
                            if (interceptor != null) {
                                interceptor.accept(
                                        object,
                                        new InlineConstructionMockContext(
                                                arguments, object.getClass(), parameterTypeNames));
                            }
                        }
                    } finally {
                        isSuspended.set(false);
                    }
                    return null;
                };

        bytecodeGenerator =
                new TypeCachingBytecodeGenerator(
                        new InlineBytecodeGenerator(
                                INSTRUMENTATION,
                                mocks,
                                mockedStatics,
                                isMockConstruction,
                                onConstruction),
                        true);
    }

    @Override
    public <T> T createMock(MockCreationSettings<T> settings, MockHandler handler) {
        return doCreateMock(settings, handler, false);
    }

    @Override
    public <T> Optional<T> createSpy(
            MockCreationSettings<T> settings, MockHandler handler, T object) {
        if (object == null) {
            throw new MockitoConfigurationException("Spy instance must not be null");
        }
        currentSpied.set(object);
        try {
            return Optional.ofNullable(doCreateMock(settings, handler, true));
        } finally {
            currentSpied.remove();
        }
    }

    private <T> T doCreateMock(
            MockCreationSettings<T> settings,
            MockHandler handler,
            boolean nullOnNonInlineConstruction) {
        Class<? extends T> type = createMockType(settings);

        try {
            T instance;
            if (settings.isUsingConstructor()) {
                instance =
                        new ConstructorInstantiator(
                                        settings.getOuterClassInstance() != null,
                                        settings.getConstructorArgs())
                                .newInstance(type);
            } else {
                try {
                    // We attempt to use the "native" mock maker first that avoids
                    // Objenesis and Unsafe
                    instance = newInstance(type);
                } catch (InstantiationException ignored) {
                    if (nullOnNonInlineConstruction) {
                        return null;
                    }
                    Instantiator instantiator =
                            Plugins.getInstantiatorProvider().getInstantiator(settings);
                    instance = instantiator.newInstance(type);
                }
            }
            MockMethodInterceptor mockMethodInterceptor =
                    new MockMethodInterceptor(handler, settings);
            mocks.put(instance, mockMethodInterceptor);
            if (instance instanceof MockAccess) {
                ((MockAccess) instance).setMockitoInterceptor(mockMethodInterceptor);
            }
            mocks.expungeStaleEntries();
            return instance;
        } catch (InstantiationException e) {
            throw new MockitoException(
                    "Unable to create mock instance of type '" + type.getSimpleName() + "'", e);
        }
    }

    @Override
    public <T> Class<? extends T> createMockType(MockCreationSettings<T> settings) {
        try {
            return bytecodeGenerator.mockClass(
                    MockFeatures.withMockFeatures(
                            settings.getTypeToMock(),
                            settings.getExtraInterfaces(),
                            settings.getSerializableMode(),
                            settings.isStripAnnotations(),
                            settings.getDefaultAnswer()));
        } catch (Exception bytecodeGenerationFailed) {
            throw prettifyFailure(settings, bytecodeGenerationFailed);
        }
    }

    private <T> RuntimeException prettifyFailure(
            MockCreationSettings<T> mockFeatures, Exception generationFailed) {
        Class<T> typeToMock = mockFeatures.getTypeToMock();
        if (typeToMock.isArray()) {
            throw new MockitoException(
                    join("Arrays cannot be mocked: " + typeToMock + ".", ""), generationFailed);
        }
        if (Modifier.isFinal(typeToMock.getModifiers())) {
            throw new MockitoException(
                    join(
                            "Mockito cannot mock this class: " + typeToMock + ".",
                            "Can not mock final classes with the following settings :",
                            " - explicit serialization (e.g. withSettings().serializable())",
                            " - extra interfaces (e.g. withSettings().extraInterfaces(...))",
                            "",
                            "You are seeing this disclaimer because Mockito is configured to create inlined mocks.",
                            "You can learn about inline mocks and their limitations under item #39 of the Mockito class javadoc.",
                            "",
                            "Underlying exception : " + generationFailed),
                    generationFailed);
        }
        if (TypeSupport.INSTANCE.isSealed(typeToMock) && typeToMock.isEnum()) {
            throw new MockitoException(
                    join(
                            "Mockito cannot mock this class: " + typeToMock + ".",
                            "Sealed abstract enums can't be mocked. Since Java 15 abstract enums are declared sealed, which prevents mocking.",
                            "You can still return an existing enum literal from a stubbed method call."),
                    generationFailed);
        }
        if (TypeSupport.INSTANCE.isSealed(typeToMock)
                && Modifier.isAbstract(typeToMock.getModifiers())) {
            throw new MockitoException(
                    join(
                            "Mockito cannot mock this class: " + typeToMock + ".",
                            "Sealed interfaces or abstract classes can't be mocked. Interfaces cannot be instantiated and cannot be subclassed for mocking purposes.",
                            "Instead of mocking a sealed interface or an abstract class, a non-abstract class can be mocked and used to represent the interface."),
                    generationFailed);
        }
        if (Modifier.isPrivate(typeToMock.getModifiers())) {
            throw new MockitoException(
                    join(
                            "Mockito cannot mock this class: " + typeToMock + ".",
                            "Most likely it is a private class that is not visible by Mockito",
                            "",
                            "You are seeing this disclaimer because Mockito is configured to create inlined mocks.",
                            "You can learn about inline mocks and their limitations under item #39 of the Mockito class javadoc.",
                            ""),
                    generationFailed);
        }
        throw new MockitoException(
                join(
                        "Mockito cannot mock this class: " + typeToMock + ".",
                        "",
                        "If you're not sure why you're getting this error, please open an issue on GitHub.",
                        "",
                        Platform.warnForVM(
                                "IBM J9 VM",
                                "Early IBM virtual machine are known to have issues with Mockito, please upgrade to an up-to-date version.\n",
                                "Hotspot",
                                ""),
                        Platform.describe(),
                        "",
                        "You are seeing this disclaimer because Mockito is configured to create inlined mocks.",
                        "You can learn about inline mocks and their limitations under item #39 of the Mockito class javadoc.",
                        "",
                        "Underlying exception : " + generationFailed),
                generationFailed);
    }

    @Override
    public MockHandler<?> getHandler(Object mock) {
        MockMethodInterceptor interceptor;
        if (mock instanceof Class<?>) {
            Map<Class<?>, MockMethodInterceptor> interceptors = mockedStatics.get();
            interceptor = interceptors != null ? interceptors.get(mock) : null;
        } else {
            interceptor = mocks.get(mock);
        }
        if (interceptor == null) {
            return null;
        } else {
            return interceptor.getMockHandler();
        }
    }

    @Override
    public void resetMock(Object mock, MockHandler newHandler, MockCreationSettings settings) {
        MockMethodInterceptor mockMethodInterceptor =
                new MockMethodInterceptor(newHandler, settings);
        if (mock instanceof Class<?>) {
            Map<Class<?>, MockMethodInterceptor> interceptors = mockedStatics.get();
            if (interceptors == null || !interceptors.containsKey(mock)) {
                throw new MockitoException(
                        "Cannot reset "
                                + mock
                                + " which is not currently registered as a static mock");
            }
            interceptors.put((Class<?>) mock, mockMethodInterceptor);
        } else {
            if (!mocks.containsKey(mock)) {
                throw new MockitoException(
                        "Cannot reset " + mock + " which is not currently registered as a mock");
            }
            mocks.put(mock, mockMethodInterceptor);
            if (mock instanceof MockAccess) {
                ((MockAccess) mock).setMockitoInterceptor(mockMethodInterceptor);
            }
            mocks.expungeStaleEntries();
        }
    }

    @Override
    public void clearAllCaches() {
        clearAllMocks();
        bytecodeGenerator.clearAllCaches();
    }

    @Override
    public void clearMock(Object mock) {
        if (mock instanceof Class<?>) {
            for (Map<Class<?>, ?> entry : mockedStatics.getBackingMap().target.values()) {
                entry.remove(mock);
            }
        } else {
            mocks.put(
                    mock,
                    new MockMethodInterceptor(
                            DisabledMockHandler.HANDLER,
                            DisabledMockHandler.HANDLER.getMockSettings()));
        }
    }

    @Override
    public void clearAllMocks() {
        mockedStatics.getBackingMap().clear();

        for (Entry<Object, MockMethodInterceptor> entry : mocks) {
            clearMock(entry.getKey());
        }
    }

    @Override
    public TypeMockability isTypeMockable(final Class<?> type) {
        return new TypeMockability() {
            @Override
            public boolean mockable() {
                return INSTRUMENTATION.isModifiableClass(type) && !EXCLUDES.contains(type);
            }

            @Override
            public String nonMockableReason() {
                if (mockable()) {
                    return "";
                }
                if (type.isPrimitive()) {
                    return "primitive type";
                }
                if (EXCLUDES.contains(type)) {
                    return "Cannot mock wrapper types, String.class or Class.class";
                }
                return "VM does not support modification of given type";
            }
        };
    }

    @Override
    public <T> StaticMockControl<T> createStaticMock(
            Class<T> type, MockCreationSettings<T> settings, MockHandler handler) {
        if (type == ConcurrentHashMap.class) {
            throw new MockitoException(
                    "It is not possible to mock static methods of ConcurrentHashMap "
                            + "to avoid infinitive loops within Mockito's implementation of static mock handling");
        } else if (type == Thread.class
                || type == System.class
                || type == Arrays.class
                || ClassLoader.class.isAssignableFrom(type)) {
            throw new MockitoException(
                    "It is not possible to mock static methods of "
                            + type.getName()
                            + " to avoid interfering with class loading what leads to infinite loops");
        }

        bytecodeGenerator.mockClassStatic(type);

        Map<Class<?>, MockMethodInterceptor> interceptors = mockedStatics.get();
        if (interceptors == null) {
            interceptors = new WeakHashMap<>();
            mockedStatics.set(interceptors);
        }
        mockedStatics.getBackingMap().expungeStaleEntries();

        return new InlineStaticMockControl<>(type, interceptors, settings, handler);
    }

    @Override
    public <T> ConstructionMockControl<T> createConstructionMock(
            Class<T> type,
            Function<MockedConstruction.Context, MockCreationSettings<T>> settingsFactory,
            Function<MockedConstruction.Context, MockHandler<T>> handlerFactory,
            MockedConstruction.MockInitializer<T> mockInitializer) {
        if (type == Object.class) {
            throw new MockitoException(
                    "It is not possible to mock construction of the Object class "
                            + "to avoid inference with default object constructor chains");
        } else if (type.isPrimitive() || Modifier.isAbstract(type.getModifiers())) {
            throw new MockitoException(
                    "It is not possible to construct primitive types or abstract types: "
                            + type.getName());
        }

        bytecodeGenerator.mockClassConstruction(type);

        Map<Class<?>, BiConsumer<Object, MockedConstruction.Context>> interceptors =
                mockedConstruction.get();
        if (interceptors == null) {
            interceptors = new WeakHashMap<>();
            mockedConstruction.set(interceptors);
        }
        mockedConstruction.getBackingMap().expungeStaleEntries();

        return new InlineConstructionMockControl<>(
                type, settingsFactory, handlerFactory, mockInitializer, interceptors);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T newInstance(Class<T> cls) throws InstantiationException {
        Constructor<?>[] constructors = cls.getDeclaredConstructors();
        if (constructors.length == 0) {
            throw new InstantiationException(cls.getName() + " does not define a constructor");
        }
        Constructor<?> selected = constructors[0];
        for (Constructor<?> constructor : constructors) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                selected = constructor;
                break;
            }
        }
        Class<?>[] types = selected.getParameterTypes();
        Object[] arguments = new Object[types.length];
        int index = 0;
        for (Class<?> type : types) {
            arguments[index++] = makeStandardArgument(type);
        }
        MemberAccessor accessor = Plugins.getMemberAccessor();
        try {
            return (T)
                    accessor.newInstance(
                            selected,
                            callback -> {
                                currentMocking.set(cls);
                                try {
                                    return callback.newInstance();
                                } finally {
                                    currentMocking.remove();
                                }
                            },
                            arguments);
        } catch (Exception e) {
            throw new InstantiationException("Could not instantiate " + cls.getName(), e);
        }
    }

    private Object makeStandardArgument(Class<?> type) {
        if (type == boolean.class) {
            return false;
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == short.class) {
            return (short) 0;
        } else if (type == char.class) {
            return (char) 0;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0f;
        } else if (type == double.class) {
            return 0d;
        } else {
            return null;
        }
    }

    private static class InlineStaticMockControl<T> implements StaticMockControl<T> {

        private final Class<T> type;

        private final Map<Class<?>, MockMethodInterceptor> interceptors;

        private final MockCreationSettings<T> settings;

        private final MockHandler<?> handler;

        private InlineStaticMockControl(
                Class<T> type,
                Map<Class<?>, MockMethodInterceptor> interceptors,
                MockCreationSettings<T> settings,
                MockHandler<?> handler) {
            this.type = type;
            this.interceptors = interceptors;
            this.settings = settings;
            this.handler = handler;
        }

        @Override
        public Class<T> getType() {
            return type;
        }

        @Override
        public void enable() {
            if (interceptors.putIfAbsent(type, new MockMethodInterceptor(handler, settings))
                    != null) {
                throw new MockitoException(
                        join(
                                "For "
                                        + type.getName()
                                        + ", static mocking is already registered in the current thread",
                                "",
                                "To create a new mock, the existing static mock registration must be deregistered"));
            }
        }

        @Override
        public void disable() {
            if (interceptors.remove(type) == null) {
                throw new MockitoException(
                        join(
                                "Could not deregister "
                                        + type.getName()
                                        + " as a static mock since it is not currently registered",
                                "",
                                "To register a static mock, use Mockito.mockStatic("
                                        + type.getSimpleName()
                                        + ".class)"));
            }
        }
    }

    private class InlineConstructionMockControl<T> implements ConstructionMockControl<T> {

        private final Class<T> type;

        private final Function<MockedConstruction.Context, MockCreationSettings<T>> settingsFactory;
        private final Function<MockedConstruction.Context, MockHandler<T>> handlerFactory;

        private final MockedConstruction.MockInitializer<T> mockInitializer;

        private final Map<Class<?>, BiConsumer<Object, MockedConstruction.Context>> interceptors;

        private final List<Object> all = new ArrayList<>();
        private int count;

        private InlineConstructionMockControl(
                Class<T> type,
                Function<MockedConstruction.Context, MockCreationSettings<T>> settingsFactory,
                Function<MockedConstruction.Context, MockHandler<T>> handlerFactory,
                MockedConstruction.MockInitializer<T> mockInitializer,
                Map<Class<?>, BiConsumer<Object, MockedConstruction.Context>> interceptors) {
            this.type = type;
            this.settingsFactory = settingsFactory;
            this.handlerFactory = handlerFactory;
            this.mockInitializer = mockInitializer;
            this.interceptors = interceptors;
        }

        @Override
        public Class<T> getType() {
            return type;
        }

        @Override
        public void enable() {
            if (interceptors.putIfAbsent(
                            type,
                            (object, context) -> {
                                ((InlineConstructionMockContext) context).count = ++count;
                                MockMethodInterceptor interceptor =
                                        new MockMethodInterceptor(
                                                handlerFactory.apply(context),
                                                settingsFactory.apply(context));
                                mocks.put(object, interceptor);
                                try {
                                    @SuppressWarnings("unchecked")
                                    T cast = (T) object;
                                    mockInitializer.prepare(cast, context);
                                } catch (Throwable t) {
                                    mocks.remove(object); // TODO: filter stack trace?
                                    throw new MockitoException(
                                            "Could not initialize mocked construction", t);
                                }
                                all.add(object);
                            })
                    != null) {
                throw new MockitoException(
                        join(
                                "For "
                                        + type.getName()
                                        + ", static mocking is already registered in the current thread",
                                "",
                                "To create a new mock, the existing static mock registration must be deregistered"));
            }
        }

        @Override
        public void disable() {
            if (interceptors.remove(type) == null) {
                throw new MockitoException(
                        join(
                                "Could not deregister "
                                        + type.getName()
                                        + " as a static mock since it is not currently registered",
                                "",
                                "To register a static mock, use Mockito.mockStatic("
                                        + type.getSimpleName()
                                        + ".class)"));
            }
            all.clear();
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<T> getMocks() {
            return (List<T>) all;
        }
    }

    private static class InlineConstructionMockContext implements MockedConstruction.Context {

        private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();

        static {
            PRIMITIVES.put(boolean.class.getName(), boolean.class);
            PRIMITIVES.put(byte.class.getName(), byte.class);
            PRIMITIVES.put(short.class.getName(), short.class);
            PRIMITIVES.put(char.class.getName(), char.class);
            PRIMITIVES.put(int.class.getName(), int.class);
            PRIMITIVES.put(long.class.getName(), long.class);
            PRIMITIVES.put(float.class.getName(), float.class);
            PRIMITIVES.put(double.class.getName(), double.class);
        }

        private int count;

        private final Object[] arguments;
        private final Class<?> type;
        private final String[] parameterTypeNames;

        private InlineConstructionMockContext(
                Object[] arguments, Class<?> type, String[] parameterTypeNames) {
            this.arguments = arguments;
            this.type = type;
            this.parameterTypeNames = parameterTypeNames;
        }

        @Override
        public int getCount() {
            if (count == 0) {
                throw new MockitoConfigurationException(
                        "mocked construction context is not initialized");
            }
            return count;
        }

        @Override
        public Constructor<?> constructor() {
            Class<?>[] parameterTypes = new Class<?>[parameterTypeNames.length];
            int index = 0;
            for (String parameterTypeName : parameterTypeNames) {
                if (PRIMITIVES.containsKey(parameterTypeName)) {
                    parameterTypes[index++] = PRIMITIVES.get(parameterTypeName);
                } else {
                    try {
                        parameterTypes[index++] =
                                Class.forName(parameterTypeName, false, type.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        throw new MockitoException(
                                "Could not find parameter of type " + parameterTypeName, e);
                    }
                }
            }
            try {
                return type.getDeclaredConstructor(parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new MockitoException(
                        join(
                                "Could not resolve constructor of type",
                                "",
                                type.getName(),
                                "",
                                "with arguments of types",
                                Arrays.toString(parameterTypes)),
                        e);
            }
        }

        @Override
        public List<?> arguments() {
            return Collections.unmodifiableList(Arrays.asList(arguments));
        }
    }
}
