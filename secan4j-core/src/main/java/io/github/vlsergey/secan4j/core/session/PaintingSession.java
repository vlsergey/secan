package io.github.vlsergey.secan4j.core.session;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.google.common.base.Functions;

import io.github.vlsergey.secan4j.core.colored.ColoredObject;
import io.github.vlsergey.secan4j.core.colored.GraphColorer;
import io.github.vlsergey.secan4j.core.colored.TraceItem;
import io.github.vlsergey.secan4j.core.colored.brushes.ColorPaintBrush;
import io.github.vlsergey.secan4j.core.colored.brushes.CompositionNodeBrush;
import io.github.vlsergey.secan4j.core.colored.brushes.CopierBrush;
import io.github.vlsergey.secan4j.core.colored.brushes.InvocationsBrush;
import io.github.vlsergey.secan4j.core.colored.brushes.InvocationsImplicitColorer;
import io.github.vlsergey.secan4j.core.colored.brushes.InvokeDynamicBrush;
import io.github.vlsergey.secan4j.core.colored.brushes.MethodParameterImplicitColorer;
import io.github.vlsergey.secan4j.core.colored.brushes.ParentAttributesDefinerBrush;
import io.github.vlsergey.secan4j.core.colorless.DataNode;
import io.github.vlsergey.secan4j.core.colorless.Invocation;
import io.github.vlsergey.secan4j.core.session.PaintingTask.Result;
import io.github.vlsergey.secan4j.core.user2command.UserToCommandInjectionColorer;
import io.github.vlsergey.secan4j.data.DataProvider;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class PaintingSession {

	@AllArgsConstructor
	@Getter
	private static class CurrentTaskInfo {
		private final PaintingTask currentTask;
		private final Set<PaintingTask> newDependencies = new HashSet<PaintingTask>(0);
	}

	private static <T> boolean hasNonNull(@NonNull T[] array) {
		for (T item : array) {
			if (item != null)
				return true;
		}
		return false;
	}

	// TODO: cleanup is surely needed
	private final @NonNull Map<PaintingTask.TaskKey, PaintingTask> allNodes = new ConcurrentHashMap<>();

	private final @NonNull ClassPool classPool;

	private final @NonNull AtomicLong currentHeapVersion = new AtomicLong(0);

	private final ThreadLocal<CurrentTaskInfo> currentTaskHolder = new ThreadLocal<>();

	private final @NonNull DataProvider dataProvider;

	private final PaintingExecutorService<PaintingTask, PaintingTask> executorService;

	private final @NonNull GraphColorer graphColorer;

	private final @NonNull BiConsumer<TraceItem, TraceItem> onSourceSinkIntersection;

	public PaintingSession(final @NonNull ClassPool classPool,
			final @NonNull BiConsumer<TraceItem, TraceItem> onSourceSinkIntersection) {
		this.onSourceSinkIntersection = onSourceSinkIntersection;

		this.executorService = new PaintingExecutorService<>(Functions.identity(), this::executeTask);

		this.classPool = classPool;
		this.dataProvider = new DataProvider();

		final UserToCommandInjectionColorer colorProvider = new UserToCommandInjectionColorer(dataProvider);

		final List<ColorPaintBrush> initialBrushes = Arrays.asList(
				new InvocationsImplicitColorer(classPool, colorProvider),
				new MethodParameterImplicitColorer(colorProvider));

		final List<ColorPaintBrush> repeatableBrushes = Arrays.asList(new CompositionNodeBrush(),
				new CopierBrush(dataProvider), new ParentAttributesDefinerBrush(dataProvider),
				new InvocationsBrush(this), new InvokeDynamicBrush());

		this.graphColorer = new GraphColorer(initialBrushes, repeatableBrushes);
	}

	public @Nullable ColoredObject[][] analyze(CtBehavior ctMethod) throws ExecutionException, InterruptedException {
		return analyze(ctMethod, null, null);
	}

	public @Nullable ColoredObject[][] analyze(CtBehavior ctMethod, ColoredObject ins[], ColoredObject outs[])
			throws ExecutionException, InterruptedException {
		PaintingTask paintingTask = new PaintingTask(ctMethod, ins, outs);
		queueImpl(paintingTask, QueueReason.ANALYZE_REQUEST);

		this.executorService.waitForAllTasksToComplete();
		log.debug("All task completed");
		final Result result = paintingTask.getResult();
		return result == null ? null : new ColoredObject[][] { result.getResultIns(), result.getResultOuts() };
	}

	protected @NonNull void executeTask(final @NonNull PaintingTask task) {
		assert currentTaskHolder.get() == null : "executeTask() is not allowed to be called recursively";
		currentTaskHolder.set(new CurrentTaskInfo(task));

		try {
			final @NonNull CtBehavior method = task.getMethod(classPool);

			if (log.isDebugEnabled())
				log.debug("(Re)coloring {} with args {}", method.getLongName(), task.getParamIns());

			// XXX: push down to arg type?
			final @NonNull CtClass ctClass = method.getDeclaringClass();

			final long usedHeapVersion = currentHeapVersion.get();
			Optional<ColoredObject[][]> opUpdated = graphColorer.color(ctClass, method, task.getParamIns(),
					task.getParamOuts(), onSourceSinkIntersection);

			final Set<PaintingTask> newDependencies = currentTaskHolder.get().getNewDependencies();

			final Set<PaintingTask> oldDependencies = task.getDependencies();
			task.setDependencies(
					newDependencies.isEmpty() ? emptySet() : unmodifiableSet(new HashSet<>(newDependencies)));

			/*
			 * update links from dependencies to task so it won't be updated on old
			 * dependencies update
			 */
			newDependencies.stream().filter(dep -> {
				return !oldDependencies.contains(dep);
			}).forEach(dep -> {
				dep.addDependant(task);
			});
			oldDependencies.stream().filter(dep -> !newDependencies.contains(dep)).forEach(dep -> {
				dep.removeDependant(task);
			});

			// TODO: here is a good place to insert cleanup callback if no task are waiting
			// for callback from task

			// schedule dependencies
			newDependencies.forEach(d -> this.queueImpl(d, QueueReason.DEPENDANT_REQUEST));

			if (opUpdated.isEmpty()) {
				log.warn("No results for deeper travel to {}", method);
				return;
			}
			final @NonNull ColoredObject[][] updated = opUpdated.get();

			final Result prevResults = task.getResult();
			if (prevResults == null || !Arrays.equals(prevResults.getResultIns(), updated[0])
					|| !Arrays.equals(prevResults.getResultOuts(), updated[1])
					|| prevResults.getVersionOfHeap() < usedHeapVersion) {
				if (log.isDebugEnabled())
					log.debug("Colors were changed after recheking {}. Store new result: {} / {}…",
							method.getLongName(), updated[0], updated[1]);

				task.setResult(new Result(updated[0], updated[1], usedHeapVersion));

				// update only if not all null
				if (hasNonNull(updated[0]) || hasNonNull(updated[1])) {
					log.debug("…and invoke update listeners: {}", task.getDependants());
					task.getDependants().forEach(d -> this.queueImpl(d, QueueReason.DEPENDENCY_UPDATE));
				} else {
					log.debug("…but skip listeners, because result is empty (colorless)");
				}
			} else {
				if (log.isDebugEnabled())
					log.debug("Colors didn't changed after recheking {}", method.getLongName());
			}

		} catch (Exception exc) {
			log.error("Unable to execute colorizing task for " + task.getMethodName() + ": " + exc.getMessage(), exc);
			// no, we don't requeue after error
		} finally {
			currentTaskHolder.remove();
		}
	}

	public @NonNull Map<DataNode, ColoredObject> getOrQueueSubcall(final @NonNull Invocation invocation,
			final @NonNull ColoredObject[] ins, final @NonNull ColoredObject[] outs) {
		assert ins.length == invocation.getParameters().length;
		assert outs.length == invocation.getResults().length;

		final CurrentTaskInfo currentTaskInfo = currentTaskHolder.get();
		assert currentTaskInfo != null : "No current task in progress in current thread. "
				+ "This method need to be called only from thread that executes executeTask() method.";

		try {
			if (log.isDebugEnabled()) {
				log.debug("Going deeper from {}(…) by analyzing {}.{}( {} ) call with args {}",
						currentTaskInfo.getCurrentTask().getMethodName(), invocation.getClassName(),
						invocation.getMethodName(), invocation.getMethodSignature(), ins);
			}

			CtClass invClass = classPool.get(invocation.getClassName());

			if (!invocation.isStaticCall() && ins[0] != null && ins[0].getSeenClassesHere().size() == 1) {
				final String refinedClassName = ins[0].getSeenClassesHere().iterator().next();

				if (!refinedClassName.equals(invocation.getClassName())) {
					// make sense only if refined class is more specific than original
					final CtClass refinedClass = classPool.get(refinedClassName);
					if (refinedClass.subtypeOf(invClass)) {
						log.debug("Refine 'this' class from {} to {}", invocation.getClassName(), refinedClassName);
						invClass = refinedClass;
					}
				}
			}

			CtBehavior invMethod = invocation.getMethodName().equals("<init>")
					? invClass.getConstructor(invocation.getMethodSignature())
					: invClass.getMethod(invocation.getMethodName(), invocation.getMethodSignature());

			if (invMethod.isEmpty()) {
				log.debug("Skip method {}(…) analysis (empty method)", invocation.getMethodName());
				return emptyMap();
			}

			final ColoredObject[] updatedIns = new ColoredObject[ins.length];
			final ColoredObject[] updatedOuts = new ColoredObject[outs.length];

			ColoredObject.demultiplex(ins, (singleClassIns) -> {

				final PaintingTask subCallTask = allNodes
						.computeIfAbsent(new PaintingTask.TaskKey(invMethod, singleClassIns, outs), PaintingTask::new);
				currentTaskInfo.getNewDependencies().add(subCallTask);

				final Result cached = subCallTask.getResult();
				if (cached != null) {
					for (int i = 0; i < ins.length; i++) {
						updatedIns[i] = ColoredObject.mergeToMostDangerous(updatedIns[i], cached.getResultIns()[i]);
					}
					for (int i = 0; i < outs.length; i++) {
						updatedOuts[i] = ColoredObject.mergeToMostDangerous(updatedOuts[i], cached.getResultOuts()[i]);
					}
				}
			});

			final Map<DataNode, ColoredObject> result = new HashMap<>(outs.length + ins.length);
			final BiConsumer<DataNode[], ColoredObject[]> toResult = (dataNodes, colors) -> {
				assert dataNodes.length == colors.length;
				for (int i = 0; i < dataNodes.length; i++) {
					if (colors[i] != null) {
						result.put(dataNodes[i], colors[i]);
					}
				}
			};

			toResult.accept(invocation.getParameters(), updatedIns);
			toResult.accept(invocation.getResults(), updatedOuts);
			return result;
		} catch (Exception exc) {
			log.warn("Unable to go deeper: " + exc.getMessage(), exc);
			return emptyMap();
		}
	}

	private synchronized void queueImpl(final PaintingTask toQueue, QueueReason reason) {
		if (toQueue.getResult() != null && reason != QueueReason.DEPENDENCY_UPDATE
				&& toQueue.getResult().getVersionOfHeap() == currentHeapVersion.get()) {
			log.debug("We have results for {}(…) and they are fresh enough", toQueue.getMethodName());
			return;
		}

		this.executorService.queue(toQueue);
	}
}
