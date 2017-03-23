package org.xvm.proto.template;

import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool.ClassConstant;

import org.xvm.proto.ObjectHandle;
import org.xvm.proto.ObjectHandle.JavaLong;
import org.xvm.proto.TypeComposition;
import org.xvm.proto.TypeCompositionTemplate;
import org.xvm.proto.TypeSet;

/**
 * TODO:
 *
 * @author gg 2017.02.27
 */
public class xBoolean
        extends TypeCompositionTemplate
    {
    public xBoolean(TypeSet types)
        {
        super(types, "x:Boolean", "x:Object", Shape.Enum);
        }

    // subclassing
    protected xBoolean(TypeSet types, String sName, String sSuper, Shape shape)
        {
        super(types, sName, sSuper, shape);
        }

    @Override
    public void initDeclared()
        {
        if (!f_sName.equals("x:Boolean")) return; // avoid recursion

        // in-place declaration for True and False
        f_types.addTemplate(new xBoolean(f_types, "x:Boolean$True", "x:Boolean", Shape.Enum));
        f_types.addTemplate(new xBoolean(f_types, "x:Boolean$False", "x:Boolean", Shape.Enum));

        TRUE = new BooleanHandle(f_clazzCanonical, true);
        FALSE = new BooleanHandle(f_clazzCanonical, false);

        //    Bit  to<Bit>();
        //    Byte to<Byte>();
        //    Int  to<Int>();
        //    UInt to<UInt>();
        //
        //    @op Boolean and(Boolean that);
        //    @op Boolean or(Boolean that);
        //    @op Boolean xor(Boolean that);
        //    @op Boolean not();

        addMethodTemplate("to", new String[]{"x:Bit"}, new String[]{"x:Bit"});
        addMethodTemplate("to", new String[]{"x:Byte"}, new String[]{"x:Byte"});
        addMethodTemplate("to", INT, INT);
        // addMethodTemplate("to", new String[]{"x:UInt64"}, new String[]{"x:UInt64"});

        addMethodTemplate("and", THIS, THIS);
        addMethodTemplate("or",  THIS, THIS);
        addMethodTemplate("xor", THIS, THIS);
        addMethodTemplate("not", VOID, THIS);
        }

    @Override
    public ObjectHandle createConstHandle(Constant constant)
        {
        if (constant instanceof ClassConstant)
            {
            ClassConstant constClass = (ClassConstant) constant;
            if (constClass.getName().equals("True"))
                {
                return TRUE;
                }
            if (constClass.getName().equals("False"))
                {
                return FALSE;
                }
            }
        return null;
        }

    @Override
    public ObjectHandle createHandle(TypeComposition clazz)
        {
        return new BooleanHandle(clazz);
        }


    public static BooleanHandle TRUE;
    public static BooleanHandle FALSE;

    private static class BooleanHandle
                extends JavaLong
        {
        BooleanHandle(TypeComposition clz)
            {
            super(clz);
            }

        BooleanHandle(TypeComposition clz, boolean f)
            {
            super(clz, f ? 1 : 0);
            }

        @Override
        public String toString()
            {
            return m_lValue > 0 ? "true" : "false";
            }
        }
    }
