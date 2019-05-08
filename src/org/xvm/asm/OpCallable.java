package org.xvm.asm;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.MethodInfo;
import org.xvm.asm.constants.SignatureConstant;
import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.TypeInfo;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;

import org.xvm.runtime.template.xException;

import static org.xvm.util.Handy.readPackedInt;
import static org.xvm.util.Handy.writePackedLong;


/**
 * Common base for CALL_ ops.
 */
public abstract class OpCallable extends Op
    {
    /**
     * Construct an op based on the passed argument.
     *
     * @param argFunction  the function Argument
     */
    protected OpCallable(Argument argFunction)
        {
        m_argFunction = argFunction;
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    protected OpCallable(DataInput in, Constant[] aconst)
            throws IOException
        {
        m_nFunctionId = readPackedInt(in);
        }

    @Override
    public void write(DataOutput out, ConstantRegistry registry)
            throws IOException
        {
        super.write(out, registry);

        if (m_argFunction != null)
            {
            m_nFunctionId = encodeArgument(m_argFunction, registry);
            }

        writePackedLong(out, m_nFunctionId);
        }

    @Override
    public boolean usesSuper()
        {
        return m_nFunctionId == A_SUPER;
        }

    /**
     * A "virtual constant" indicating whether or not this op has multiple return values.
     *
     * @return true iff the op has multiple return values.
     */
    protected boolean isMultiReturn()
        {
        return false;
        }

    @Override
    public void resetSimulation()
        {
        if (isMultiReturn())
            {
            resetRegisters(m_aArgReturn);
            }
        else
            {
            resetRegister(m_argReturn);
            }
        }

    @Override
    public void simulate(Scope scope)
        {
        if (isMultiReturn())
            {
            checkNextRegisters(scope, m_aArgReturn);
            }
        else
            {
            checkNextRegister(scope, m_argReturn);
            }
        }

    @Override
    public void registerConstants(ConstantRegistry registry)
        {
        m_argFunction = registerArgument(m_argFunction, registry);

        if (isMultiReturn())
            {
            registerArguments(m_aArgReturn, registry);
            }
        else
            {
            m_argReturn = registerArgument(m_argReturn, registry);
            }
        }

    /**
     * This Op holds a constant for the constructor of a child of the compile-time parent.
     * The run-time type of the parent could extend the compile-time type and that parent
     * may have a corresponding child extension.
     *
     * @return a child constructor for the specified parent
     */
    protected MethodStructure getVirtualConstructor(Frame frame, ObjectHandle hParent)
        {
        // suffix "C" indicates the compile-time constants; "R" - the run-time
        ClassConstant idParentR = hParent.getTemplate().getClassConstant();
        if (m_constructor != null)
            {
            if (m_idParent.equals(idParentR))
                {
                // cached constructor fits the parent's class
                return m_constructor;
                }
            }

        MethodStructure constructor = getMethodStructure(frame);
        ClassStructure  clzTargetC  = (ClassStructure) constructor.getParent().getParent();
        ClassStructure  clzParentC  = (ClassStructure) clzTargetC.getParent();
        ClassConstant   idParentC   = (ClassConstant) clzParentC.getIdentityConstant();

        if (!idParentR.equals(idParentC))
            {
            // find the run-time target's constructor;
            // note that we don't need to resolve the actual types
            String         sChild      = clzTargetC.getSimpleName();
            ClassStructure clzParentR  = (ClassStructure) idParentR.getComponent();
            ClassStructure clzChild    = clzParentR.getVirtualChild(sChild);
            TypeInfo       infoTarget  = clzChild.getFormalType().ensureTypeInfo();

            MethodInfo info = infoTarget.getMethodBySignature(
                constructor.getIdentityConstant().getSignature());
            constructor = info.getTopmostMethodStructure(infoTarget);
            }

        m_idParent    = idParentR;
        m_constructor = constructor;
        return constructor;
        }

    /**
     * @return an exception handle
     */
    protected ExceptionHandle reportMissingConstructor(Frame frame, ObjectHandle hParent)
        {
        ClassConstant     idParentR      = hParent.getTemplate().getClassConstant();
        SignatureConstant sigConstructor = getMethodStructure(frame).getIdentityConstant().getSignature();

        return xException.makeHandle("Missing constructor \"" + sigConstructor.getValueString() +
                                     "\" at class " + idParentR.getValueString());
        }

    @Override
    public String toString()
        {
        return super.toString() + ' ' + getFunctionString() + '(' + getParamsString() + ") -> " + getReturnsString();
        }
    protected String getFunctionString()
        {
        return Argument.toIdString(m_argFunction, m_nFunctionId);
        }
    protected String getParamsString()
        {
        return "";
        }
    protected static String getParamsString(int[] anArgValue, Argument[] aArgValue)
        {
        StringBuilder sb = new StringBuilder();
        int cArgNums = anArgValue == null ? 0 : anArgValue.length;
        int cArgRefs = aArgValue == null ? 0 : aArgValue.length;
        for (int i = 0, c = Math.max(cArgNums, cArgRefs); i < c; ++i)
            {
            if (i > 0)
                {
                sb.append(", ");
                }
            sb.append(Argument.toIdString(i < cArgRefs ? aArgValue[i] : null,
                    i < cArgNums ? anArgValue[i] : Register.UNKNOWN));
            }
        return sb.toString();
        }
    protected String getReturnsString()
        {
        if (m_anRetValue != null || m_aArgReturn != null)
            {
            // multi-return
            StringBuilder sb = new StringBuilder();
            int cArgNums = m_anRetValue == null ? 0 : m_anRetValue.length;
            int cArgRefs = m_aArgReturn == null ? 0 : m_aArgReturn.length;
            for (int i = 0, c = Math.max(cArgNums, cArgRefs); i < c; ++i)
                {
                sb.append(i == 0 ? "(" : ", ")
                        .append(Argument.toIdString(i < cArgRefs ? m_aArgReturn[i] : null,
                                i < cArgNums ? m_anRetValue[i] : Register.UNKNOWN));
                }
            return sb.append(')').toString();
            }

        if (m_nRetValue != A_IGNORE || m_argReturn != null)
            {
            return Argument.toIdString(m_argReturn, m_nRetValue);
            }

        return "void";
        }

    // ----- helper methods -----

    // get the structure for the function constant
    protected MethodStructure getMethodStructure(Frame frame)
        {
        // there is no need to cache the id, since it's a constant for a given op-code
        if (m_function != null)
            {
            return m_function;
            }

        MethodConstant constFunction = (MethodConstant) frame.getConstant(m_nFunctionId);

        return m_function = (MethodStructure) constFunction.getComponent();
        }

    // return the corresponding VirtualChildType constant
    protected TypeConstant getCanonicalChildType(Frame frame, TypeConstant typeParent, String sName)
        {
        return frame.poolContext().ensureVirtualChildTypeConstant(typeParent, sName);
        }

    // check if a register for the return value needs to be allocated
    protected void checkReturnRegister(Frame frame, MethodStructure method)
        {
        assert !isMultiReturn();

        if (frame.isNextRegister(m_nRetValue))
            {
            frame.introduceMethodReturnVar(m_nRetValue, method.getIdentityConstant().getPosition(), 0);
            }
        }

    // check if a register for the return Tuple value needs to be allocated
    protected void checkReturnTupleRegister(Frame frame, MethodStructure method)
        {
        assert !isMultiReturn();

        if (frame.isNextRegister(m_nRetValue))
            {
            frame.introduceMethodReturnVar(m_nRetValue, method.getIdentityConstant().getPosition(), 0);
            }
        }

    // check if any registers for the return values need to be allocated
    protected void checkReturnRegisters(Frame frame, MethodStructure method)
        {
        assert isMultiReturn();

        int[] anRet = m_anRetValue;
        for (int i = 0, c = anRet.length; i < c; i++)
            {
            if (frame.isNextRegister(anRet[i]))
                {
                frame.introduceMethodReturnVar(anRet[i], method.getIdentityConstant().getPosition(), i);
                }
            }
        }

    protected int   m_nFunctionId;
    protected int   m_nRetValue = A_IGNORE;
    protected int[] m_anRetValue;

    protected Argument   m_argFunction;
    protected Argument   m_argReturn;  // optional
    protected Argument[] m_aArgReturn; // optional

    private MethodStructure m_function;   // cached function

    private ClassConstant   m_idParent;    // the parent's class id for the cached constructor
    private MethodStructure m_constructor; // cached constructor
    }
