package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import java.util.List;

import org.xvm.asm.ConstantPool;
import org.xvm.asm.Constants.Access;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.PropertyStructure;
import org.xvm.asm.Component;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.compiler.Compiler;
import org.xvm.compiler.Compiler.Stage;
import org.xvm.compiler.Token;

import org.xvm.util.Severity;

import static org.xvm.util.Handy.appendString;
import static org.xvm.util.Handy.indentLines;


/**
 * A property declaration.
 */
public class PropertyDeclarationStatement
        extends ComponentStatement
    {
    // ----- constructors --------------------------------------------------------------------------

    public PropertyDeclarationStatement(long             lStartPos,
                                        long             lEndPos,
                                        Expression       condition,
                                        List<Token>      modifiers,
                                        List<Annotation> annotations,
                                        TypeExpression   type,
                                        Token            name,
                                        Expression       value,
                                        StatementBlock   body,
                                        Token            doc)
        {
        super(lStartPos, lEndPos);

        this.condition   = condition;
        this.modifiers   = modifiers;
        this.annotations = annotations;
        this.type        = type;
        this.name        = name;
        this.value       = value;
        this.body        = body;
        this.doc         = doc;
        }


    // ----- accessors -----------------------------------------------------------------------------

    public String getName()
        {
        return name.getValue().toString();
        }

    /**
     * @return true iff the property is declared as static
     */
    public boolean isStatic()
        {
        // properties inside a method are ALWAYS specified as static, but NEVER actually static (in
        // the "constant property" sense)
        if (getParent().getComponent() instanceof MethodStructure)
            {
            return false;
            }

        List<Token> list = modifiers;
        if (list != null && list.isEmpty())
            {
            for (Token token : list)
                {
                if (token.getId() == Token.Id.STATIC)
                    {
                    return true;
                    }
                }
            }

        return false;
        }

    @Override
    public Access getDefaultAccess()
        {
        Access access = getAccess(modifiers);
        return access == null
                ? super.getDefaultAccess()
                : access;
        }

    public Access getVarAccess()
        {
        if (modifiers != null && !modifiers.isEmpty())
            {
            Access access = null;
            for (Token modifier : modifiers)
                {
                switch (modifier.getId())
                    {
                    case PUBLIC:
                        if (access == null)
                            {
                            access = Access.PUBLIC;
                            }
                        else
                            {
                            return Access.PUBLIC;
                            }
                        break;

                    case PROTECTED:
                        if (access == null)
                            {
                            access = Access.PROTECTED;
                            }
                        else
                            {
                            return Access.PROTECTED;
                            }
                        break;

                    case PRIVATE:
                        if (access == null)
                            {
                            access = Access.PRIVATE;
                            }
                        else
                            {
                            return Access.PRIVATE;
                            }
                        break;

                    }
                }

            if (access != null)
                {
                return access;
                }
            }

        return getDefaultAccess();
        }

    @Override
    protected Field[] getChildFields()
        {
        return CHILD_FIELDS;
        }


    // ----- compile phases ------------------------------------------------------------------------

    @Override
    protected void registerStructures(ErrorListener errs)
        {
        // create the structure for this property
        if (getComponent() == null)
            {
            // create a structure for this type
            String sName = (String) name.getValue();
            Component container = getParent().getComponent();
            if (container.isClassContainer())
                {
                // another property by the same name should not already exist, but the check for
                // duplicates is deferred, since it is possible (thanks to the complexity of
                // conditionals) to have multiple components occupying the same location within the
                // namespace at this point in the compilation
                // TODO if (container.getProperty(sName) != null) ...

                TypeConstant      constType = type.ensureTypeConstant();
                PropertyStructure prop      = container.createProperty(
                        isStatic(), getDefaultAccess(), getVarAccess(), constType, sName);
                setComponent(prop);

                // introduce the unresolved type constant to the type expression, so that when the
                // type expression resolves, it can resolve the unresolved type constant
                type.setTypeConstant(constType);

                // the annotations either have to be registered on the type or on the property, so
                // register them on the property for now (they'll get sorted out later after we
                // can resolve the types to figure out what the annotation targets actually are)
                if (annotations != null)
                    {
                    ConstantPool pool = pool();
                    for (Annotation annotation : annotations)
                        {
                        prop.addAnnotation(annotation.buildAnnotation(pool));
                        }
                    }
                }
            else
                {
                log(errs, Severity.ERROR, Compiler.PROP_UNEXPECTED, sName, container);
                }
            }

        super.registerStructures(errs);
        }

    @Override
    public void resolveNames(List<AstNode> listRevisit, ErrorListener errs)
        {
        if (getStage().ordinal() < Stage.Resolved.ordinal())
            {
            super.resolveNames(listRevisit, errs);
            }

        PropertyStructure struct = (PropertyStructure) getComponent();
        if (!struct.resolveAnnotations())
            {
            listRevisit.add(this);
            }
        }


    // ----- debugging assistance ------------------------------------------------------------------

    public String toSignatureString()
        {
        StringBuilder sb = new StringBuilder();

        if (modifiers != null)
            {
            for (Token token : modifiers)
                {
                sb.append(token.getId().TEXT)
                  .append(' ');
                }
            }

        if (annotations != null)
            {
            for (Annotation annotation : annotations)
                {
                sb.append(annotation)
                  .append(' ');
                }
            }

        sb.append(type)
          .append(' ')
          .append(name.getValue());

        return sb.toString();
        }

    @Override
    public String toString()
        {
        StringBuilder sb = new StringBuilder();

        if (doc != null)
            {
            String sDoc = String.valueOf(doc.getValue());
            if (sDoc.length() > 100)
                {
                sDoc = sDoc.substring(0, 97) + "...";
                }
            appendString(sb.append("/*"), sDoc).append("*/\n");
            }

        sb.append(toSignatureString());

        if (value != null)
            {
            sb.append(" = ")
              .append(value)
              .append(";");
            }
        else if (body != null)
            {
            String sBody = body.toString();
            if (sBody.indexOf('\n') >= 0)
                {
                sb.append('\n')
                  .append(indentLines(sBody, "    "));
                }
            else
                {
                sb.append(' ')
                  .append(sBody);
                }
            }
        else
            {
            sb.append(';');
            }

        return sb.toString();
        }

    @Override
    public String getDumpDesc()
        {
        return toSignatureString();
        }


    // ----- fields --------------------------------------------------------------------------------

    protected Expression         condition;
    protected List<Token>        modifiers;
    protected List<Annotation>   annotations;
    protected TypeExpression     type;
    protected Token              name;
    protected Expression         value;
    protected StatementBlock     body;
    protected Token              doc;

    private static final Field[] CHILD_FIELDS = fieldsForNames(PropertyDeclarationStatement.class,
            "condition", "annotations", "type", "value", "body");
    }
