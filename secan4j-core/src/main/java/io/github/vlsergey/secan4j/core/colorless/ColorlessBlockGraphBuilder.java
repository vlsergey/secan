package io.github.vlsergey.secan4j.core.colorless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Descriptor;
import javassist.bytecode.InstructionPrinter;
import javassist.bytecode.Mnemonic;
import javassist.bytecode.Opcode;
import javassist.bytecode.SignatureAttribute;
import javassist.bytecode.SignatureAttribute.MethodSignature;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;
import javassist.bytecode.analysis.Frame;
import javassist.bytecode.analysis.Type;
import lombok.Data;
import lombok.NonNull;
import lombok.SneakyThrows;

public class ColorlessBlockGraphBuilder {

	@Data
	private static class BlockDataGraphKey {
		final Block block;
		final DataNode[] incLocalNodes;
		final Collection<DataNode> incStackNodes;
	}

	private interface Heap {
		DataNode getField(DataNode ref, String fieldRef);

		void putField(DataNode ref, String fieldRef, DataNode value);
	}

	static final DataNode CONST_INT_0 = new DataNode("int 0").setType(Type.INTEGER);
	static final DataNode CONST_INT_1 = new DataNode("int 1").setType(Type.INTEGER);
	static final DataNode CONST_INT_2 = new DataNode("int 2").setType(Type.INTEGER);
	static final DataNode CONST_INT_3 = new DataNode("int 3").setType(Type.INTEGER);
	static final DataNode CONST_INT_4 = new DataNode("int 4").setType(Type.INTEGER);
	static final DataNode CONST_INT_5 = new DataNode("int 5").setType(Type.INTEGER);

	static final DataNode[] CONST_INTS = new DataNode[] { CONST_INT_0, CONST_INT_1, CONST_INT_2, CONST_INT_3,
			CONST_INT_4, CONST_INT_5 };

	static final DataNode CONST_LONG_0 = new DataNode("long 0").setType(Type.LONG);

	static final DataNode CONST_LONG_1 = new DataNode("long 1").setType(Type.LONG);

	static final DataNode CONST_NULL = new DataNode("null").setType(Type.UNINIT);

	// TODO: replace with slf4j?
	private static boolean TRACE = false;

	static void assertIntAlike(DataNode dataNode) {
		final Type actual = dataNode.type;
		assert actual == Type.BYTE || actual == Type.CHAR || actual == Type.INTEGER
				: "Expected int-alike, but found " + actual;
	}

	static void assertSameSizeOnFrameStack(Deque<DataNode> actual, Frame expected) {
		if (actual.isEmpty()) {
			assert expected.getTopIndex() == -1;
			return;
		}

		int topIndex = expected.getTopIndex();
		assert actual.stream().map(DataNode::getType).mapToInt(Type::getSize).sum() == (topIndex + 1)
				: "Size of DataNode stack is " + actual.stream().map(DataNode::getType).mapToInt(Type::getSize).sum()
						+ ", but expected size was " + (topIndex + 1);
	}

	static void assertSameTypeOnFrameLocals(DataNode[] actual, Frame expected) {
		for (int i = 0; i < actual.length; i++) {
			DataNode dataNode = actual[i];
			if (dataNode == null) {
				// dataNode is not assigned...
				// it may be assigned later or it's part of "wide" type in another cell
			} else {
				assert (dataNode.getType() == expected.getLocal(i))
						|| (expected.getLocal(i).getCtClass().isPrimitive()
								&& dataNode.getType().getCtClass().isPrimitive())
						|| expected.getLocal(i).isAssignableFrom(dataNode.getType());
			}
		}
	}

	private void processInstructionWithStackOnly(final @NonNull Deque<DataNode> currentStack, final int toPoll) {
		processInstructionWithStackOnly(currentStack, toPoll,
				() -> new DataNode(InstructionPrinter.instructionString(methodCodeIterator, index, methodConstPool)));
	}

	private void processInstructionWithStackOnly(final @NonNull Deque<DataNode> currentStack, final int toPoll,
			Supplier<DataNode> resultTypeSupplier) {
		DataNode result = resultTypeSupplier.get().setOperation(getCurrentOp()).setType(getTypeOfNextStackTop());
		DataNode[] inputs = new DataNode[toPoll];
		for (int i = 0; i < toPoll; i++) {
			inputs[i] = currentStack.pop();
		}
		result.inputs = inputs;
		currentStack.push(result);
	}

	private final @NonNull ClassPool classPool;
	private final @NonNull CodeIterator methodCodeIterator;
	private final @NonNull ConstPool methodConstPool;
	private final @NonNull ControlFlow methodControlFlow;
	private final @NonNull Block block;

	public ColorlessBlockGraphBuilder(final @NonNull ClassPool classPool,
			final @NonNull CodeIterator methodCodeIterator, final @NonNull ConstPool methodConstPool,
			final @NonNull ControlFlow methodControlFlow, final @NonNull Block block) {
		super();
		this.classPool = classPool;
		this.methodCodeIterator = methodCodeIterator;
		this.methodConstPool = methodConstPool;
		this.methodControlFlow = methodControlFlow;
		this.block = block;
	}

	private int index = -1;

	@SneakyThrows
	private Type getTypeOfNextStackTop() {
		final Frame nextInstructionFrame = methodControlFlow.frameAt(methodCodeIterator.lookAhead());
		final int topIndex = nextInstructionFrame.getTopIndex();
		final Type onTopOfStack = nextInstructionFrame.getStack(topIndex);
		if (onTopOfStack == Type.TOP) {
			final Type beforeTop = nextInstructionFrame.getStack(topIndex - 1);
			assert beforeTop.getSize() == 2;
			return beforeTop;
		}
		return onTopOfStack;
	}

	@SneakyThrows
	public BlockDataGraph buildGraph(final @NonNull DataNode[] incLocalNodes, final @NonNull Deque<DataNode> incStack) {
		final int firstPos = block.position();
		final int length = block.length();

		final DataNode[] currentLocals = Arrays.copyOf(incLocalNodes, incLocalNodes.length);
		final Deque<DataNode> currentStack = new LinkedList<>(incStack);

		DataNode toReturn = null;
		List<Invocation> invokations = new ArrayList<>();
		List<PutFieldNode> putFieldNodes = new ArrayList<>();
		List<PutStaticNode> putStaticNodes = new ArrayList<>();

		final Set<DataNode> allNodesSet = new LinkedHashSet<>();
		allNodesSet.addAll(currentStack);
		allNodesSet.addAll(Arrays.asList(currentLocals));

		methodCodeIterator.move(firstPos);

		if (TRACE) {
			System.out.println();
//			System.out.println("Method: " + ctMethod.getLongName());
			System.out.println("Block: " + block);

			final Frame frame = methodControlFlow.frameAt(block.position());
			System.out.println("Frame: " + frame);
			System.out.println();

			System.out.println("DN Stack: ");
			currentStack.forEach(dn -> System.out.println(" * " + dn));
			System.out.println("DN Locals: ");
			Arrays.asList(currentLocals).forEach(dn -> System.out.println(" * " + dn));

		}

		assertSameTypeOnFrameLocals(currentLocals, methodControlFlow.frameAt(block.position()));
		assertSameSizeOnFrameStack(currentStack, methodControlFlow.frameAt(block.position()));

		while (methodCodeIterator.hasNext() && (index = methodCodeIterator.next()) < firstPos + length) {
			if (TRACE) {
				System.out.println(index + ":\t"
						+ InstructionPrinter.instructionString(methodCodeIterator, index, methodConstPool));
				System.out.println(methodControlFlow.frameAt(index));
				System.out.println("* Before: ");
				System.out.println("* * Locals: ");
				Arrays.asList(currentLocals).forEach(dn -> System.out.println("* * * " + dn));
				System.out.println("* * Stack: ");
				currentStack.forEach(dn -> System.out.println("* * * " + dn));
			}

			assertSameTypeOnFrameLocals(currentLocals, methodControlFlow.frameAt(index));
			assertSameSizeOnFrameStack(currentStack, methodControlFlow.frameAt(index));

			toReturn = processInstruction(new Heap() {

				@Override
				public DataNode getField(DataNode ref, String fieldRef) {
					throw new UnsupportedOperationException("NYI");
				}

				@Override
				public void putField(DataNode ref, String fieldRef, DataNode value) {
					throw new UnsupportedOperationException("NYI");
				}

			}, currentLocals, currentStack, toReturn, invokations, putFieldNodes, putStaticNodes);

			allNodesSet.addAll(currentStack);
			allNodesSet.addAll(Arrays.asList(currentLocals));

			int nextIndex = methodCodeIterator.lookAhead();
			if (nextIndex < firstPos + length) {
				final Frame nextFrame = methodControlFlow.frameAt(nextIndex);

				if (TRACE) {
					System.out.println("* After: ");
					System.out.println("* " + nextFrame);
					System.out.println("* * Locals: ");
					Arrays.asList(currentLocals).forEach(dn -> System.out.println("* * * " + dn));
					System.out.println("* * Stack: ");
					currentStack.forEach(dn -> System.out.println("* * * " + dn));
					System.out.println();
				}

				assertSameTypeOnFrameLocals(currentLocals, nextFrame);
				assertSameSizeOnFrameStack(currentStack, nextFrame);
			}
		}

		allNodesSet.remove(null);

		return new BlockDataGraph(allNodesSet.toArray(DataNode[]::new), incLocalNodes, incStack,
				invokations.toArray(Invocation[]::new), DataNode.EMPTY_DATA_NODES, DataNode.EMPTY_DATA_NODES,
				currentLocals, toReturn == null ? DataNode.EMPTY_DATA_NODES : new DataNode[] { toReturn }, currentStack,
				putFieldNodes.toArray(PutFieldNode[]::new), putStaticNodes.toArray(PutStaticNode[]::new));
	}

	private Type getType(final ClassPool classPool, final ConstPool constPool, int tagIndex)
			throws NotFoundException, BadBytecode {
		int tag = constPool.getTag(tagIndex);
		Type type;
		switch (tag) {
		case ConstPool.CONST_String:
			type = Type.get(classPool.get("java.lang.String"));
			break;
		case ConstPool.CONST_Integer:
			type = Type.INTEGER;
			break;
		case ConstPool.CONST_Float:
			type = Type.FLOAT;
			break;
		case ConstPool.CONST_Long:
			type = Type.LONG;
			break;
		case ConstPool.CONST_Double:
			type = Type.DOUBLE;
			break;
		case ConstPool.CONST_Class:
			type = Type.get(classPool.get("java.lang.Class"));
			break;
		default:
			throw new BadBytecode("bad LDC [pos = " + tagIndex + "]: " + tag);
		}
		return type;
	}

	// TODO: extract to class with fields... too many arguments
	@SneakyThrows
	private DataNode processInstruction(final Heap heap, final DataNode[] currentLocals,
			final Deque<DataNode> currentStack, DataNode toReturn, final List<Invocation> invokations,
			final List<PutFieldNode> putFieldNodes, final List<PutStaticNode> putStaticNodes) {

		int op = getCurrentOp();
		switch (op) {
		case Opcode.ACONST_NULL:
			currentStack.push(CONST_NULL);
			break;

		case Opcode.ALOAD:
			currentStack.push(currentLocals[methodCodeIterator.byteAt(index + 1)]);
			break;

		case Opcode.ALOAD_0:
		case Opcode.ALOAD_1:
		case Opcode.ALOAD_2:
		case Opcode.ALOAD_3:
			currentStack.push(currentLocals[op - Opcode.ALOAD_0]);
			break;

		case Opcode.ARETURN:
			// expected to be last of the instructions
			toReturn = currentStack.pop();
			break;

		case Opcode.ARRAYLENGTH:
			processInstructionWithStackOnly(currentStack, 1);
			break;

		case Opcode.ASTORE:
			currentLocals[methodCodeIterator.byteAt(index + 1)] = currentStack.pop();
			break;

		case Opcode.ASTORE_0:
		case Opcode.ASTORE_1:
		case Opcode.ASTORE_2:
		case Opcode.ASTORE_3:
			currentLocals[op - Opcode.ASTORE_0] = currentStack.pop();
			break;

		case Opcode.ATHROW:
			final DataNode toThrow = currentStack.peek();
			currentStack.clear();
			currentStack.push(toThrow);
			break;

		case Opcode.BALOAD:
		case Opcode.CALOAD:
		case Opcode.DALOAD:
		case Opcode.FALOAD:
		case Opcode.IALOAD:
		case Opcode.LALOAD:
		case Opcode.SALOAD: {
			processInstructionWithStackOnly(currentStack, 2);
			break;
		}

		case Opcode.BASTORE:
		case Opcode.CASTORE:
		case Opcode.DASTORE:
		case Opcode.FASTORE:
		case Opcode.IASTORE:
		case Opcode.LASTORE:
		case Opcode.SASTORE:
			currentStack.pop();
			currentStack.pop();
			currentStack.pop();
			break;

		case Opcode.BIPUSH:
			processInstructionWithStackOnly(currentStack, 0);
			break;

		case Opcode.CHECKCAST:
			processInstructionWithStackOnly(currentStack, 1);
			break;

		case Opcode.DLOAD: {
			final int varIndex = methodCodeIterator.byteAt(index + 1);
			currentStack.push(currentLocals[varIndex]);
			break;
		}
		case Opcode.DLOAD_0:
		case Opcode.DLOAD_1:
		case Opcode.DLOAD_2:
		case Opcode.DLOAD_3:
			currentStack.push(currentLocals[op - Opcode.DLOAD_0]);
			break;

		case Opcode.DSTORE: {
			final int varIndex = methodCodeIterator.byteAt(index + 1);
			currentLocals[varIndex] = currentStack.pop();
			break;
		}
		case Opcode.DSTORE_0:
		case Opcode.DSTORE_1:
		case Opcode.DSTORE_2:
		case Opcode.DSTORE_3:
			currentLocals[op - Opcode.DSTORE_0] = currentStack.pop();
			break;

		case Opcode.DUP:
			currentStack.push(currentStack.peek());
			break;

		case Opcode.FLOAD:
			currentStack.push(currentLocals[methodCodeIterator.byteAt(index + 1)]);
			assert currentStack.peek().type == Type.FLOAT;
			break;

		case Opcode.FLOAD_0:
		case Opcode.FLOAD_1:
		case Opcode.FLOAD_2:
		case Opcode.FLOAD_3:
			currentStack.push(currentLocals[op - Opcode.FLOAD_0]);
			assert currentStack.peek().type == Type.FLOAT;
			break;

		case Opcode.GETFIELD:
		case Opcode.GETSTATIC: {
			int constantIndex = methodCodeIterator.u16bitAt(index + 1);
			final String fieldrefClassName = methodConstPool.getFieldrefClassName(constantIndex);
			final String fieldrefName = methodConstPool.getFieldrefName(constantIndex);
			final String fieldrefType = methodConstPool.getFieldrefType(constantIndex);

			final CtClass fieldClass = classPool.get(fieldrefClassName);
			final CtField ctField = fieldClass.getField(fieldrefName, fieldrefType);

			// XXX: USE HEAP
			if (op == Opcode.GETFIELD) {
				processInstructionWithStackOnly(currentStack, 1, () -> new GetFieldNode(fieldClass, ctField));
			} else {
				processInstructionWithStackOnly(currentStack, 0, () -> new GetStaticNode(fieldClass, ctField));
			}
			break;
		}

		case Opcode.GOTO:
			// nothing is changed in data
			break;

		case Opcode.I2B:
		case Opcode.I2C:
		case Opcode.I2D:
		case Opcode.I2F:
		case Opcode.I2L:
		case Opcode.I2S:
			processInstructionWithStackOnly(currentStack, 1);
			break;

		case Opcode.IADD:
		case Opcode.IAND:
		case Opcode.IDIV:
		case Opcode.IMUL:
		case Opcode.IOR:
		case Opcode.IREM:
		case Opcode.ISHL:
		case Opcode.ISHR:
		case Opcode.ISUB:
		case Opcode.IUSHR:
		case Opcode.IXOR:
			processInstructionWithStackOnly(currentStack, 2);
			break;

		case Opcode.ICONST_0:
		case Opcode.ICONST_1:
		case Opcode.ICONST_2:
		case Opcode.ICONST_3:
		case Opcode.ICONST_4:
		case Opcode.ICONST_5:
			currentStack.push(CONST_INTS[op - Opcode.ICONST_0]);
			break;

		case Opcode.IF_ACMPEQ:
		case Opcode.IF_ACMPNE:
		case Opcode.IF_ICMPEQ:
		case Opcode.IF_ICMPGE:
		case Opcode.IF_ICMPGT:
		case Opcode.IF_ICMPLE:
		case Opcode.IF_ICMPLT:
		case Opcode.IF_ICMPNE:
			currentStack.pop();
			currentStack.pop();
			break;

		case Opcode.IFEQ:
		case Opcode.IFGE:
		case Opcode.IFGT:
		case Opcode.IFLE:
		case Opcode.IFLT:
		case Opcode.IFNE:
		case Opcode.IFNONNULL:
		case Opcode.IFNULL:
			currentStack.pop();
			break;

		case Opcode.IINC: {
			DataNode prevValue = currentLocals[methodCodeIterator.byteAt(index + 1)];
			DataNode nextValue = new DataNode(
					InstructionPrinter.instructionString(methodCodeIterator, index, methodConstPool))
							.setInputs(new DataNode[] { prevValue }).setOperation(op).setType(prevValue.getType());
			currentLocals[methodCodeIterator.byteAt(index + 1)] = nextValue;
			break;
		}

		case Opcode.ILOAD:
			currentStack.push(currentLocals[methodCodeIterator.byteAt(index + 1)]);
			break;
		case Opcode.ILOAD_0:
		case Opcode.ILOAD_1:
		case Opcode.ILOAD_2:
		case Opcode.ILOAD_3:
			currentStack.push(currentLocals[op - Opcode.ILOAD_0]);
			break;

		case Opcode.INEG:
		case Opcode.INSTANCEOF: {
			processInstructionWithStackOnly(currentStack, 1);
			break;
		}

		case Opcode.INVOKEDYNAMIC: {
			int constantIndex = methodCodeIterator.u16bitAt(index + 1);

			final int nameAndType = methodConstPool.getInvokeDynamicNameAndType(constantIndex);
			final String signature = methodConstPool.getUtf8Info(methodConstPool.getNameAndTypeDescriptor(nameAndType));

			final MethodSignature methodSignature = SignatureAttribute.toMethodSignature(signature);
			processInstructionWithStackOnly(currentStack, methodSignature.getParameterTypes().length);
			break;
		}

		case Opcode.INVOKEINTERFACE:
		case Opcode.INVOKESPECIAL:
		case Opcode.INVOKESTATIC:
		case Opcode.INVOKEVIRTUAL: {
			int constantIndex = methodCodeIterator.u16bitAt(index + 1);

			String className = null;
			String methodName = null;
			String signature = null;

			/*
			 * invokes an interface method on object objectref and puts the result on the
			 * stack (might be void); the interface method is identified by method reference
			 * index in constant pool (indexbyte1 << 8 | indexbyte2)
			 */
			if (op == Opcode.INVOKEINTERFACE || op == Opcode.INVOKESPECIAL || op == Opcode.INVOKEVIRTUAL
					|| op == Opcode.INVOKESTATIC) {
				className = methodConstPool.getMethodrefClassName(constantIndex);
				methodName = methodConstPool.getMethodrefName(constantIndex);
				signature = methodConstPool.getMethodrefType(constantIndex);
			} else {
				final int nameAndType = methodConstPool.getInvokeDynamicNameAndType(constantIndex);
				methodName = methodConstPool.getUtf8Info(methodConstPool.getNameAndTypeName(nameAndType));
				signature = methodConstPool.getUtf8Info(methodConstPool.getNameAndTypeDescriptor(nameAndType));
			}

			CtClass[] params = Descriptor.getParameterTypes(signature, classPool);
			CtClass retType = Descriptor.getReturnType(signature, classPool);

			List<DataNode> inputs = new ArrayList<>();
			if (op != Opcode.INVOKESTATIC) {
				// objectref
				inputs.add(currentStack.pop());
			}
			for (int i = 0; i < params.length; i++) {
				inputs.add(currentStack.pop());
			}
			Collections.reverse(inputs);
			final DataNode[] inputsArray = inputs.toArray(new DataNode[inputs.size()]);

			DataNode result = null;
			if (!CtClass.voidType.equals(retType)) {
				result = new DataNode("result of invoke");
				result.inputs = inputsArray;
				result.operation = op;
				result.type = Type.get(retType);
				currentStack.push(result);
			}

			invokations.add(new Invocation(className, methodName, signature, inputsArray,
					result == null ? DataNode.EMPTY_DATA_NODES : new DataNode[] { result }, op == Opcode.INVOKESTATIC));
			break;
		}

		case Opcode.IRETURN: {
			toReturn = currentStack.pop();
			break;
		}

		case Opcode.ISTORE:
			currentLocals[methodCodeIterator.byteAt(index + 1)] = currentStack.pop();
			break;

		case Opcode.ISTORE_0:
		case Opcode.ISTORE_1:
		case Opcode.ISTORE_2:
		case Opcode.ISTORE_3:
			currentLocals[op - Opcode.ISTORE_0] = currentStack.pop();
			break;

		case Opcode.LADD:
		case Opcode.LAND:
		case Opcode.LCMP:
			processInstructionWithStackOnly(currentStack, 2);
			break;

		case Opcode.LCONST_0:
			currentStack.push(CONST_LONG_0);
			break;
		case Opcode.LCONST_1:
			currentStack.push(CONST_LONG_1);
			break;

		case Opcode.LDC:
		case Opcode.LDC_W: {
			int tagIndex = op == Opcode.LDC ? methodCodeIterator.byteAt(index + 1)
					: methodCodeIterator.u16bitAt(index + 1);
			String description = "constant #" + tagIndex;

			Type type = getType(classPool, methodConstPool, tagIndex);
			if (type.getCtClass().getName().equals("java.lang.String")) {
				description = "\"" + methodConstPool.getStringInfo(tagIndex) + "\"";
			}

			currentStack.push(new DataNode(description).setType(type));
			break;
		}

		case Opcode.LDC2_W: {
			int tagIndex = methodCodeIterator.u16bitAt(index + 1);
			String description = "constant #" + tagIndex;

			Type type = getType(classPool, methodConstPool, tagIndex);
			if (type.getCtClass().getName().equals("java.lang.String")) {
				description = "\"" + methodConstPool.getUtf8Info(index) + "\"";
			}

			currentStack.push(new DataNode(description).setType(type));
			break;
		}

		case Opcode.LLOAD: {
			final int varIndex = methodCodeIterator.byteAt(index + 1);
			currentStack.push(currentLocals[varIndex]);
			break;
		}
		case Opcode.LLOAD_0:
		case Opcode.LLOAD_1:
		case Opcode.LLOAD_2:
		case Opcode.LLOAD_3:
			currentStack.push(currentLocals[op - Opcode.LLOAD_0]);
			break;

		case Opcode.LSTORE: {
			final int varIndex = methodCodeIterator.byteAt(index + 1);
			currentLocals[varIndex] = currentStack.pop();
			break;
		}
		case Opcode.LSTORE_0:
		case Opcode.LSTORE_1:
		case Opcode.LSTORE_2:
		case Opcode.LSTORE_3:
			currentLocals[op - Opcode.LSTORE_0] = currentStack.pop();
			break;

		case Opcode.NEW:
			processInstructionWithStackOnly(currentStack, 0);
			break;
		case Opcode.NEWARRAY:
			processInstructionWithStackOnly(currentStack, 1);
			break;

		case Opcode.POP: {
			DataNode removed = currentStack.pop();
			assert removed.getType().getSize() == 1;
			break;
		}

		case Opcode.POP2: {
			DataNode removed = currentStack.pop();
			if (removed.getType().getSize() != 2) {
				DataNode removed2 = currentStack.pop();
				assert removed2.getType().getSize() == 1;
			}
			break;
		}

		case Opcode.PUTFIELD:
		case Opcode.PUTSTATIC: {
			int constantIndex = methodCodeIterator.u16bitAt(index + 1);
			final String fieldrefClassName = methodConstPool.getFieldrefClassName(constantIndex);
			final String fieldrefName = methodConstPool.getFieldrefName(constantIndex);
			final String fieldrefType = methodConstPool.getFieldrefType(constantIndex);

			final CtClass fieldClass = classPool.get(fieldrefClassName);
			final CtField ctField = fieldClass.getField(fieldrefName, fieldrefType);

			// XXX: USE HEAP
			if (op == Opcode.PUTFIELD) {
				putFieldNodes.add(new PutFieldNode(fieldClass, ctField, currentStack.pop(), currentStack.pop()));
			} else {
				putStaticNodes.add(new PutStaticNode(fieldClass, ctField, currentStack.pop()));
			}
			break;
		}

		case Opcode.SIPUSH:
			int constantValue = methodCodeIterator.u16bitAt(index + 1);
			currentStack.push(new DataNode("shoart as int " + constantValue).setOperation(op).setType(Type.INTEGER));
			break;

		case Opcode.RETURN:
			// return void
			toReturn = null;
			break;

		default:
			throw new UnsupportedOperationException("Unknown opcode: " + Mnemonic.OPCODE[op]);
		}
		return toReturn;
	}

	private int getCurrentOp() {
		return methodCodeIterator.byteAt(index);
	}

}