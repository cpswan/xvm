package org.xvm.asm.ast;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.xvm.asm.ast.LanguageAST.ExprAST;

import static org.xvm.asm.ast.LanguageAST.NodeType.CONSTANT_EXPR;

import static org.xvm.util.Handy.readMagnitude;
import static org.xvm.util.Handy.writePackedLong;


/**
 * An expression that yields a constant value.
 */
public class ConstantExprAST<C>
        extends ExprAST<C> {

    private C type;
    private C value;

    ConstantExprAST() {}

    public ConstantExprAST(C type, C value) {
        assert type != null && value != null;
        this.type  = type;
        this.value = value;
    }

    public C getType() {
        return type;
    }

    public C getValue() {
        return value;
    }

    @Override
    public C getType(int i) {
        assert i == 0;
        return type;
    }

    @Override
    public NodeType nodeType() {
        return CONSTANT_EXPR;
    }

    @Override
    public void read(DataInput in, ConstantResolver<C> res)
            throws IOException {
        type  = res.getConstant(readMagnitude(in));
        value = res.getConstant(readMagnitude(in));
    }

    @Override
    public void prepareWrite(ConstantResolver<C> res) {
        type  = res.register(type);
        value = res.register(value);
    }

    @Override
    public void write(DataOutput out, ConstantResolver<C> res)
            throws IOException {
        out.writeByte(nodeType().ordinal());

        writePackedLong(out, res.indexOf(type));
        writePackedLong(out, res.indexOf(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}