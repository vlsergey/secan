package io.github.vlsergey.secan4j.core.session;

import static java.util.Collections.emptyMap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import com.google.common.base.Functions;

import io.github.vlsergey.secan4j.core.colored.ColoredObject;
import io.github.vlsergey.secan4j.core.colored.GraphColorer;
import io.github.vlsergey.secan4j.core.colored.TraceItem;
import io.github.vlsergey.secan4j.core.colorless.ColorlessGraphBuilder;
import io.github.vlsergey.secan4j.core.colorless.DataNode;
import io.github.vlsergey.secan4j.core.colorless.Invocation;
import io.github.vlsergey.secan4j.core.session.PaintingTask.Result;
import io.github.vlsergey.secan4j.core.user2command.UserToCommandInjectionColorer;
import io.github.vlsergey.secan4j.data.DataProvider;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class PaintingSession {

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

	private final PaintingExecutorService<PaintingTask, PaintingTask> executorService;

	private final @NonNull GraphColorer graphColorer;

	private final @NonNull BiConsumer<TraceItem, TraceItem> onSourceSinkIntersection;

	public PaintingSession(final @NonNull ClassPool classPool,
			final @NonNull BiConsumer<TraceItem, TraceItem> onSourceSinkIntersection) {
		this.onSourceSinkIntersection = onSourceSinkIntersection;

		this.executorService = new PaintingExecutorService<>(Functions.identity(), this::executeTask);

		this.classPool = classPool;
		this.graphColorer = new GraphColorer(classPool, new ColorlessGraphBuilder(),
				new UserToCommandInjectionColorer(new DataProvider()));
	}

	public void analyze(CtBehavior ctMethod) throws ExecutionException, InterruptedException {
		PaintingTask paintingTask = new PaintingTask(ctMethod);
		queueImpl(paintingTask);

		this.executorService.waitForAllTasksToComplete();
		log.debug("All task completed");
	}

	protected @NonNull void executeTask(final @NonNull PaintingTask task) {
		try {
			final @NonNull CtBehavior method = task.getMethod(classPool);

			if (log.isDebugEnabled())
				log.debug("(Re)coloring {}...", method.getLongName());

			// XXX: push down to arg type?
			final @NonNull CtClass ctClass = method.getDeclaringClass();

			final long usedHeapVersion = currentHeapVersion.get();
			final Set<PaintingTask> newDependencies = new HashSet<>();
			Optional<ColoredObject[][]> opUpdated = graphColorer.color(ctClass, method, task.getParamIns(),
					task.getParamOuts(),
					(subInvokation, ins, outs) -> getOrQueueSubcall(subInvokation, ins, outs, task, newDependencies),
					onSourceSinkIntersection);

			final Set<PaintingTask> oldDependencies = task.getDependencies();
			task.setDependencies(newDependencies);

			/*
			 * update links from dependencies to task so it won't be updated on old
			 * dependencies update
			 */
			newDependencies.stream().filter(dep -> !oldDependencies.contains(dep))
					.forEach(dep -> dep.addDependant(task));
			oldDependencies.stream().filter(dep -> !newDependencies.contains(dep))
					.forEach(dep -> dep.removeDependant(task));

			// TODO: here is a good place to insert cleanup callback if no task are waiting
			// for callback from task

			// schedule dependencies
			newDependencies.forEach(this::queueImpl);

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
					log.debug("Colors were changed after recheking {}. Store new result�", method.getLongName());

				task.setResult(new Result(updated[0], updated[1], usedHeapVersion));

				// update only if not all null
				if (hasNonNull(updated[0]) || hasNonNull(updated[1])) {
					log.debug("�and invoke update listeners");
					task.getDependants().forEach(this::queueImpl);
				} else {
					log.debug("�but skip listeners, because result is empty (colorless)");
				}
			} else {
				if (log.isDebugEnabled())
					log.debug("Colors didn't changed after recheking {}", method.getLongName());
			}

		} catch (Exception exc) {
			log.error("Unable to execute colorizing task for " + task.getMethodName() + ": " + exc.getMessage(), exc);
			// no, we don't requeue after error
		}
	}

	private @NonNull Map<DataNode, ColoredObject> getOrQueueSubcall(final @NonNull Invocation invocation,
			final @NonNull ColoredObject[] ins, final @NonNull ColoredObject[] outs, PaintingTask prevTask,
			final @NonNull Set<PaintingTask> newDependencies) {
		assert ins.length == invocation.getParameters().length;
		assert outs.length == invocation.getResults().length;

		try {
			log.debug("Going deeper from {}(�) by analyzing {}.{}(�) call",
					prevTask.getMethod(this.classPool).getName(), invocation.getClassName(),
					invocation.getMethodName());

			CtClass invClass = classPool.get(invocation.getClassName());
			CtBehavior invMethod = invocation.getMethodName().equals("<init>")
					? invClass.getConstructor(invocation.getMethodSignature())
					: invClass.getMethod(invocation.getMethodName(), invocation.getMethodSignature());

			if (invMethod.isEmpty()) {
				return emptyMap();
			}

			final ColoredObject[] updatedIns = new ColoredObject[ins.length];
			final ColoredObject[] updatedOuts = new ColoredObject[outs.length];

			ColoredObject.demultiplex(ins, (singleClassIns) -> {

				final PaintingTask subCallTask = allNodes.computeIfAbsent(new PaintingTask.TaskKey(invMethod),
						PaintingTask::new);
				newDependencies.add(subCallTask);

				final Result cached = subCallTask.getResult();
				if (cached != null) {
					for (int i = 0; i < ins.length; i++) {
						updatedIns[i] = ColoredObject.merge(updatedIns[i], cached.getResultIns()[i], null);
					}
					for (int i = 0; i < outs.length; i++) {
						updatedOuts[i] = ColoredObject.merge(updatedOuts[i], cached.getResultOuts()[i], null);
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

	private synchronized void queueImpl(final PaintingTask toQueue) {
		if (toQueue.getResult() != null && toQueue.getResult().getVersionOfHeap() == currentHeapVersion.get()) {
			log.debug("We have results for {}() and they are fresh enough", toQueue.getMethodName());
			return;
		}

		this.executorService.queue(toQueue);
	}
}
