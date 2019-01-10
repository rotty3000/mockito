/*
 * Copyright (c) 2016 Mockito contributors
 * This program is made available under the terms of the MIT License.
 */
package org.mockito.internal.creation.bytebuddy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.SynchronizationState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.MultipleParentClassLoader;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.attribute.MethodAttributeAppender;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.RandomString;
import org.mockito.Mockito;
import org.mockito.exceptions.base.MockitoException;
import org.mockito.internal.creation.bytebuddy.ByteBuddyCrossClassLoaderSerializationSupport.CrossClassLoaderSerializableMock;
import org.mockito.internal.creation.bytebuddy.MockMethodInterceptor.DispatcherDefaultingToRealMethod;
import org.mockito.mock.SerializableMode;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;

import static java.lang.Thread.currentThread;
import static net.bytebuddy.description.modifier.Visibility.PRIVATE;
import static net.bytebuddy.dynamic.Transformer.ForMethod.withModifiers;
import static net.bytebuddy.implementation.MethodDelegation.to;
import static net.bytebuddy.implementation.attribute.MethodAttributeAppender.ForInstrumentedMethod.INCLUDING_RECEIVER;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.mockito.internal.util.StringUtil.join;

class SubclassBytecodeGenerator implements BytecodeGenerator {

    private static final String CODEGEN_PACKAGE = "org.mockito.codegen.";

    private static final Object MOCKITO_MODULE;
    private static final Method GET_MODULE, IS_OPEN, CAN_READ, ADD_READ;

    static {
        Object mockitoModule;
        Method getModule, isOpen, canRead, addRead;
        try {
            getModule = Class.class.getMethod("getModule");
            isOpen = getModule.getReturnType().getMethod("isOpen", String.class, getModule.getReturnType());
            canRead = getModule.getReturnType().getMethod("canRead", getModule.getReturnType());
            addRead = getModule.getReturnType().getMethod("addReads", getModule.getReturnType());
            mockitoModule = getModule.invoke(Mockito.class);
        } catch (Throwable ignored) {
            mockitoModule = null;
            getModule = null;
            isOpen = null;
            canRead = null;
            addRead = null;
        }
        MOCKITO_MODULE = mockitoModule;
        GET_MODULE = getModule;
        IS_OPEN = isOpen;
        CAN_READ = canRead;
        ADD_READ = addRead;
    }

    private final SubclassLoader loader;

    private final ByteBuddy byteBuddy;
    private final Random random;

    private final Implementation readReplace;
    private final ElementMatcher<? super MethodDescription> matcher;

    private final Implementation dispatcher = to(DispatcherDefaultingToRealMethod.class);
    private final Implementation hashCode = to(MockMethodInterceptor.ForHashCode.class);
    private final Implementation equals = to(MockMethodInterceptor.ForEquals.class);
    private final Implementation writeReplace = to(MockMethodInterceptor.ForWriteReplace.class);

    public SubclassBytecodeGenerator() {
        this(new SubclassInjectionLoader());
    }

    public SubclassBytecodeGenerator(SubclassLoader loader) {
        this(loader, null, any());
    }

    public SubclassBytecodeGenerator(Implementation readReplace, ElementMatcher<? super MethodDescription> matcher) {
        this(new SubclassInjectionLoader(), readReplace, matcher);
    }

    protected SubclassBytecodeGenerator(SubclassLoader loader, Implementation readReplace, ElementMatcher<? super MethodDescription> matcher) {
        this.loader = loader;
        this.readReplace = readReplace;
        this.matcher = matcher;
        byteBuddy = new ByteBuddy().with(TypeValidation.DISABLED);
        random = new Random();
    }

    @Override
    public <T> Class<? extends T> mockClass(MockFeatures<T> features) {
        ClassLoader classLoader = new MultipleParentClassLoader.Builder()
            .append(features.mockedType)
            .append(features.interfaces)
            .append(currentThread().getContextClassLoader())
            .append(MockAccess.class, DispatcherDefaultingToRealMethod.class)
            .append(MockMethodInterceptor.class).build(MockMethodInterceptor.class.getClassLoader());
        String name = nameFor(features.mockedType, classLoader);
        DynamicType.Builder<T> builder =
                byteBuddy.subclass(features.mockedType)
                         .name(name)
                         .ignoreAlso(isGroovyMethod())
                         .annotateType(features.stripAnnotations
                             ? new Annotation[0]
                             : features.mockedType.getAnnotations())
                         .implement(new ArrayList<Type>(features.interfaces))
                         .method(matcher)
                           .intercept(dispatcher)
                           .transform(withModifiers(SynchronizationState.PLAIN))
                           .attribute(features.stripAnnotations
                               ? MethodAttributeAppender.NoOp.INSTANCE
                               : INCLUDING_RECEIVER)
                         .method(isHashCode())
                           .intercept(hashCode)
                         .method(isEquals())
                           .intercept(equals)
                         .serialVersionUid(42L)
                         .defineField("mockitoInterceptor", MockMethodInterceptor.class, PRIVATE)
                         .implement(MockAccess.class)
                           .intercept(FieldAccessor.ofBeanProperty());
        if (features.serializableMode == SerializableMode.ACROSS_CLASSLOADERS) {
            builder = builder.implement(CrossClassLoaderSerializableMock.class)
                             .intercept(writeReplace);
        }
        if (readReplace != null) {
            builder = builder.defineMethod("readObject", void.class, Visibility.PRIVATE)
                    .withParameters(ObjectInputStream.class)
                    .throwing(ClassNotFoundException.class, IOException.class)
                    .intercept(readReplace);
        }
        if (classLoader != features.mockedType.getClassLoader()) {
            assertVisibility(features.mockedType);
            for (Class<?> iFace : features.interfaces) {
                assertVisibility(iFace);
            }
            builder = builder.ignoreAlso(isPackagePrivate()
                .or(returns(isPackagePrivate()))
                .or(hasParameters(whereAny(hasType(isPackagePrivate())))));
        } else if (!canReadMockito(features.mockedType)) {
            addReadEdgeByProbe(features.mockedType);
        }
        return builder.make()
                      .load(classLoader, loader.resolveStrategy(features.mockedType, classLoader, name.startsWith(CODEGEN_PACKAGE)))
                      .getLoaded();
    }

    private void addReadEdgeByProbe(Class<?> type) {
        try {
            byteBuddy.subclass(Runnable.class)
                    .name(String.format("%s$%s$%d", type.getName(), "MockitoModuleProbe", Math.abs(random.nextInt())))
                    .invokable(named("run")).intercept(MethodCall.invoke(ADD_READ)
                    .onMethodCall(MethodCall.invoke(GET_MODULE).on(type))
                    .withMethodCall(MethodCall.invoke(GET_MODULE).on(Mockito.class)))
                    .make()
                    .load(type.getClassLoader(), loader.resolveStrategy(type, type.getClassLoader(), false))
                    .getLoaded()
                    .getConstructor()
                    .newInstance()
                    .run();
        } catch (Exception e) {
            throw new MockitoException(join("Could not force initialization of " + type,
                "",
                "This is required to adjust the module graph to enable mock creation"), e);
        }
    }

    private static ElementMatcher<MethodDescription> isGroovyMethod() {
        return isDeclaredBy(named("groovy.lang.GroovyObjectSupport"));
    }

    private String nameFor(Class<?> type, ClassLoader classLoader) {
        String typeName;
        if (classLoader == type.getClassLoader()
            ? !isOpenedToMockito(type)
            : isComingFromJDK(type) || isComingFromSignedJar(type) || isComingFromSealedPackage(type)) {
            typeName = CODEGEN_PACKAGE + type.getSimpleName();
        } else {
            typeName = type.getName();
        }
        return String.format("%s$%s$%d", typeName, "MockitoMock", Math.abs(random.nextInt()));
    }

    private boolean isComingFromJDK(Class<?> type) {
        // Comes from the manifest entry :
        // Implementation-Title: Java Runtime Environment
        // This entry is not necessarily present in every jar of the JDK
        return type.getPackage() != null && "Java Runtime Environment".equalsIgnoreCase(type.getPackage().getImplementationTitle())
                || type.getName().startsWith("java.")
                || type.getName().startsWith("javax.");
    }

    private boolean isComingFromSealedPackage(Class<?> type) {
        return type.getPackage() != null && type.getPackage().isSealed();
    }

    private boolean isOpenedToMockito(Class<?> type) {
        if (type.getPackage() == null) {
            return true;
        }
        try {
            return MOCKITO_MODULE != null && (Boolean) IS_OPEN.invoke(GET_MODULE.invoke(type), type.getPackage().getName(), MOCKITO_MODULE);
        } catch (Exception e) {
            throw new MockitoException(join("Cannot assert if " + type + " is opened to Mockito",
                "",
                "Error during reflective access of expected methods of the Java module system"
            ), e);
        }
    }

    private boolean canReadMockito(Class<?> type) {
        if (type.getPackage() == null) {
            return true;
        }
        try {
            return MOCKITO_MODULE != null && (Boolean) CAN_READ.invoke(GET_MODULE.invoke(type), MOCKITO_MODULE);
        } catch (Exception e) {
            throw new MockitoException(join("Cannot assert if " + type + " is opened to Mockito",
                "",
                "Error during reflective access of expected methods of the Java module system"
            ), e);
        }
    }

    private boolean isComingFromSignedJar(Class<?> type) {
        return type.getSigners() != null;
    }

    private static void assertVisibility(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            throw new MockitoException(join("Cannot create mock for " + type,
                "",
                "The type is not public and its mock class is loaded by a different class loader.",
                "This can have multiple reasons:",
                " - You are mocking a class with additional interfaces of another class loader",
                " - Mockito is loaded by a different class loader than the mocked type (e.g. with OSGi)",
                " - The thread's context class loader is different than the mock's class loader"));
        }
    }
}
