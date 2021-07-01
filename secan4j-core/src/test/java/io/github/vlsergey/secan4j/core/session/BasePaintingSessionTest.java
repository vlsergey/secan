package io.github.vlsergey.secan4j.core.session;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import javax.annotation.Nullable;

import io.github.vlsergey.secan4j.core.colored.ColorType;
import io.github.vlsergey.secan4j.core.colored.ColoredObject;
import io.github.vlsergey.secan4j.core.colored.Confidence;
import io.github.vlsergey.secan4j.core.colored.PaintedColor;
import io.github.vlsergey.secan4j.core.colored.TraceItem;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BasePaintingSessionTest {

	@NonNull
	protected static final BiConsumer<TraceItem, TraceItem> noIntersectionExpected = (source, sink) -> {
		fail("We din't expect intersection to be found here");
	};

	protected static final TraceItem testTraceItem = new TraceItem() {

		@Override
		public Map<String, ?> describe() {
			return singletonMap("description", "test source");
		}

		@Override
		public TraceItem findPrevious() {
			return null;
		}
	};

	protected static CtBehavior getCtMethod(final CtClass ctClass, final String methodName, final String signature) {
		try {
			if (signature == null) {
				return "<init>".equals(methodName) ? ctClass.getConstructors()[0]
						: Arrays.stream(ctClass.getMethods()).filter(m -> m.getName().equals(methodName)).findFirst()
								.get();
			}

			return "<init>".equals(methodName) ? ctClass.getConstructor(signature)
					: ctClass.getMethod(methodName, signature);
		} catch (NotFoundException exc) {
			if (methodName != "<init>") {
				Arrays.stream(ctClass.getMethods()).filter(m -> m.getName().equals(methodName)).forEach(
						m -> log.info("Found method with name {} and signature {}", methodName, m.getSignature()));
				fail("no method '" + methodName + "' with signature '" + signature + "' found");
			} else {
				Arrays.stream(ctClass.getConstructors())
						.forEach(c -> log.info("Found constructor with signature {}", methodName, c.getSignature()));
				fail("no constructor '" + methodName + "' with signature '" + signature + "' found");
			}
			throw new AssertionError();
		}
	}

	protected static <S, R> R[] map(S[] src, Function<S, R> func, IntFunction<R[]> arraySupplier) {
		return Arrays.stream(src).map(x -> x == null ? null : func.apply(x)).toArray(arraySupplier);
	}

	protected static @Nullable ColoredObject[] toColoredObjects(final CtClass[] classes,
			final @Nullable ColorType[] colors) throws NotFoundException {
		if (colors == null) {
			return null;
		}

		final @NonNull ColoredObject[] inObjects = new ColoredObject[colors.length];
		for (int i = 0; i < colors.length; i++) {
			inObjects[i] = colors[i] == null ? null
					: ColoredObject.forRootOnly(classes[i],
							new PaintedColor(Confidence.EXPLICITLY, testTraceItem, colors[i]));
		}
		return inObjects;
	}

	protected static ColorType[] toColorType(ColoredObject[] colors) {
		return map(colors, c -> c.getColor().getType(), ColorType[]::new);
	}

	protected static ColorType[][] toColorType(ColoredObject[][] colors) {
		return map(colors, BasePaintingSessionTest::toColorType, ColorType[][]::new);
	}

	protected final ClassPool classPool = ClassPool.getDefault();

	@NonNull
	protected ColorType[][] analyze(final @NonNull Class<?> cls, final @NonNull String methodName) throws Exception {
		return analyze(cls, methodName, null, null, null, null, noIntersectionExpected);
	}

	@NonNull
	protected ColorType[][] analyze(final @NonNull Class<?> cls, final @NonNull String methodName,
			final @Nullable String signature, ColorType[] ins, ColorType[] outs) throws Exception {
		return analyze(cls, methodName, signature, ins, outs, null, noIntersectionExpected);
	}

	@NonNull
	protected ColorType[][] analyze(final @NonNull Class<?> cls, final @NonNull String methodName,
			final @Nullable String signature, @Nullable ColorType[] ins, @Nullable ColorType[] outs,
			@Nullable Class<?>[] inTypes) throws Exception {
		return analyze(cls, methodName, signature, ins, outs, inTypes, noIntersectionExpected);
	}

	@NonNull
	protected ColorType[][] analyze(final @NonNull Class<?> cls, final @NonNull String methodName,
			final @Nullable String signature, @Nullable ColorType[] ins, @Nullable ColorType[] outs,
			@Nullable Class<?>[] inTypes, final @NonNull BiConsumer<TraceItem, TraceItem> onSourceSinkIntersection)
			throws Exception {
		final CtClass ctClass = classPool.get(cls.getName());
		final CtBehavior ctBehavior = getCtMethod(ctClass, methodName, signature);

		if (outs != null) {
			assert outs.length < 2;
			assert (outs.length == 1) == (ctBehavior instanceof CtMethod
					&& ((CtMethod) ctBehavior).getReturnType() != CtClass.voidType);
		}

		PaintingSession paintingSession = new PaintingSession(classPool,
				onSourceSinkIntersection == null ? noIntersectionExpected : onSourceSinkIntersection);

		final CtClass[] actualInTypes;
		if (!(ctBehavior instanceof CtMethod) || !Modifier.isStatic(((CtMethod) ctBehavior).getModifiers())) {
			actualInTypes = new CtClass[ctBehavior.getParameterTypes().length + 1];
			actualInTypes[0] = ctClass;
			System.arraycopy(ctBehavior.getParameterTypes(), 0, actualInTypes, 1,
					ctBehavior.getParameterTypes().length);
		} else {
			actualInTypes = ctBehavior.getParameterTypes();
		}
		if (inTypes != null) {
			for (int i = 0; i < inTypes.length; i++) {
				if (inTypes[i] != null) {
					actualInTypes[i] = classPool.get(inTypes[i].getName());
				}
			}
		}

		final ColoredObject[][] analyzeResult = paintingSession.analyze(ctBehavior,
				toColoredObjects(actualInTypes, ins),
				outs == null ? null
						: outs.length == 0 ? new ColoredObject[0]
								: toColoredObjects(new CtClass[] { ((CtMethod) ctBehavior).getReturnType() }, outs));

		final ColoredObject[][] result = analyzeResult != null ? analyzeResult
				: new ColoredObject[][] { new ColoredObject[ins.length], new ColoredObject[outs.length] };
		return toColorType(result);
	}

}