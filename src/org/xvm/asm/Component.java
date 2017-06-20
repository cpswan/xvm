package org.xvm.asm;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import org.xvm.asm.constants.CharStringConstant;
import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.ConditionalConstant;
import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.ModuleConstant;
import org.xvm.asm.constants.MultiMethodConstant;
import org.xvm.asm.constants.NamedConstant;
import org.xvm.asm.constants.PackageConstant;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.util.LinkedIterator;
import org.xvm.util.ListMap;

import static org.xvm.util.Handy.readIndex;
import static org.xvm.util.Handy.readMagnitude;
import static org.xvm.util.Handy.writePackedLong;


/**
 * The Component data structure is the base class for the module, package, class, property, method,
 * and other data structures that make up the structural components of the module format. The
 * Component encapsulates a number of complex aspects of the XVM module format, most notably the
 * conditional structure of XVM modules.
 * <p/>
 * The Component has an identity, tracks some standard flags / settings, and keeps track of whether
 * it has been modified.
 * <p/>
 * Here is the Component containment model, with container type on the left and containee type
 * across the top:
 * <p/>
 * <code><pre>
 *           Module  Package  Class  MultiMethod  Method  Property  |  Conditional
 * File        x                                                    |
 * Module              x       x       x                    x       |    x (version only)
 * Package             x       x       x                    x       |    x
 * Class                       x       x                    x       |    x
 * Property                            x                            |    x
 * MultiMethod                                      x               |
 * Method                      x       x                    x       |    x
 * </pre></code>
 * <p/>
 * Based on the containment model, of these types, there are three groups of containment:
 * <ul>
 * <li><i>(Multi-)Method</i></li>
 * <li><i>(Multi-)Method + Property + Class</i></li>
 * <li><i>(Multi-)Method + Property + Class + Package</i></li>
 * </ul>
 * <p/>
 * Normally, an XVM structure has a single parent structure and any number of child structures, but
 * a Component can differ dramatically from this model, in that it can have any number of parent
 * Conponents (only one of which at most is valid for a given condition), and it can have any number
 * of child Components, of which only some are (perhaps none is) appropriate for a given condition.
 * <p/>
 * The persistent form of a Component is relatively complicated, in order to handle the potentially
 * conditional nature of the component, the ability to have several components (called siblings)
 * that share the same spot in the namespace (based on condition), and the ability to defer the
 * deserialization of the component's children. The first byte of the persistent component either
 * has the {@link #CONDITIONAL_BIT} set or it doesn't; if the bit is not set, then the first byte
 * is actually the first byte of the component's "body", but if it is set, then the next value in
 * the stream is the number of siblings, followed by pairs of condition constant id and component
 * body. After the component body (or after the last component body, if the condition bit was set),
 * there is a length-encoded "children" section, which contains all of the nested components; a
 * length of 0 indicates that there are no children. The children section is composed of the number
 * of child components, followed by a sequence of that many components.
 *
 * @author cp 2017.05.02
 */
public abstract class Component
        extends XvmStructure
        implements Documentable
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a component.
     *
     * @param xsParent    the parent, or null if this is a file structure
     * @param access      the accessibility of this component
     * @param fAbstract   the abstractness of this component
     * @param fStatic     the staticness of this component
     * @param fSynthetic  the syntheticness of this component
     * @param format      the format (meta type for the structure) of this component
     * @param constId     the constant that identifies this component, or null if this is a file
     *                    structure
     * @param condition   the optional condition that mandates the existence of this structure
     */
    protected Component(XvmStructure xsParent, Access access, boolean fAbstract, boolean fStatic,
                        boolean fSynthetic, Format format, IdentityConstant constId, ConditionalConstant condition)
        {
        this(xsParent, (format.ordinal() << FORMAT_SHIFT) | (access.ordinal() << ACCESS_SHIFT)
                | (fAbstract ? ABSTRACT_BIT : 0) | (fStatic ? STATIC_BIT : 0)
                | (fSynthetic ? SYNTHETIC_BIT : 0), constId, condition);
        }

    /**
     * Construct a component.
     *
     * @param xsParent    the parent, or null if this is a file structure
     * @param nFlags      the flags specifying the accessibility, abstractness, staticness,
     *                    syntheticness, and the format of this component
     * @param constId     the constant that identifies this component, or null if this is a file
     *                    structure
     * @param condition   the optional condition that mandates the existence of this structure
     */
    protected Component(XvmStructure xsParent, int nFlags, IdentityConstant constId, ConditionalConstant condition)
        {
        super(xsParent);
        assert (xsParent == null) == (this instanceof FileStructure);   // file doesn't have parent
        assert (constId == null) == (this instanceof FileStructure);    // file doesn't have constid
        assert condition == null || !(this instanceof FileStructure);   // file can't be conditional

        m_nFlags  = (short) nFlags;
        m_constId = constId;
        m_cond    = condition;
        }

    /**
     * Package private constructor used by the CompositeComponent.
     *
     * @param parent  the parent of the composite component
     */
    Component(Component parent)
        {
        super(parent);
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * Each Component has a parent. The one exception is the file structure, which is not contained
     * within another component.
     * <p/>
     * This is a far more complex request than it first appears, because of the possibility of
     * conditions. While an XvmStructure has one and only one Containing XvmStructure, each
     * component can have more than one parent, depending on the condition. Imagine for example
     * a protected class that contains a method, and in a subsequent version the class is modified
     * to be a public class; from the method's point of view, there are two different classes that
     * are its parent, depending on the current assembler context. (It would be an assertion error
     * to ask such a question if the assembler context were not configured accordingly.)
     *
     * @return the parent Component of this Component
     */
    public Component getParent()
        {
        Component    parent     = null;
        XvmStructure containing = getContaining();
        while (true)
            {
            if (containing == null)
                {
                assert getFormat() == Format.FILE;
                return null;
                }

            if (containing instanceof Component)
                {
                parent = (Component) containing;
                break;
                }

            containing = containing.getContaining();
            }

        if (parent.getCondition() == null)
            {
            // if there is no condition on the parent, then there is only one possible parent;
            // note that a file component cannot have a condition
            return parent;
            }
        assert parent.getFormat() != Format.FILE;

        // need to get the grandparent and ask it for the parent
        Component grandparent = parent.getParent();
        return parent instanceof MethodStructure
                ? grandparent.getChild(parent.getIdentityConstant())
                : grandparent.getChild(parent.getName());
        }

    /**
     * Each Component is identified by a constant. The one exception is the file structure, which
     * contains components, but is not technically itself a component in the XVM sense.
     *
     * @return  the constant that identifies the component, or null for a File component
     */
    public Constant getIdentityConstant()
        {
        return m_constId;
        }

    /**
     * @return the Format that corresponds to this Component
     */
    public Format getFormat()
        {
        return Format.valueOf((m_nFlags & FORMAT_MASK) >>> FORMAT_SHIFT);
        }

    /**
     * @return the Access for this Component
     */
    public Access getAccess()
        {
        return Access.valueOf((m_nFlags & ACCESS_MASK) >>> ACCESS_SHIFT);
        }

    /**
     * Specify the accessibility of the component.
     *
     * @param access  the accessibility of the component
     */
    public void setAccess(Access access)
        {
        int nFlagsOld = m_nFlags;
        int nFlagsNew = (nFlagsOld & ~ACCESS_MASK) | (access.ordinal() << ACCESS_SHIFT);
        if (nFlagsNew != nFlagsOld)
            {
            m_nFlags = (short) nFlagsNew;
            markModified();
            }
        }

    /**
     * @return true iff the component is marked as abstract
     */
    public boolean isAbstract()
        {
        return (m_nFlags & ABSTRACT_BIT) != 0;
        }

    /**
     * Specify whether or not the component is abstract.
     * 
     * @param fAbstract  true to specify the component is abstract; false otherwise
     */
    public void setAbstract(boolean fAbstract)
        {
        int nFlagsOld = m_nFlags;
        int nFlagsNew = (nFlagsOld & ~ABSTRACT_BIT) | (fAbstract ? ABSTRACT_BIT : 0);
        if (nFlagsNew != nFlagsOld)
            {
            m_nFlags = (short) nFlagsNew;
            markModified();
            }
        }
    
    /**
     * @return true iff the component is marked as static
     */
    public boolean isStatic()
        {
        return (m_nFlags & STATIC_BIT) != 0;
        }

    /**
     * Specify whether or not the component is static.
     *
     * @param fStatic  true to specify the component is static; false otherwise
     */
    public void setStatic(boolean fStatic)
        {
        int nFlagsOld = m_nFlags;
        int nFlagsNew = (nFlagsOld & ~STATIC_BIT) | (fStatic ? STATIC_BIT : 0);
        if (nFlagsNew != nFlagsOld)
            {
            m_nFlags = (short) nFlagsNew;
            markModified();
            }
        }

    /**
     * @return true iff the component is marked as synthetic
     */
    public boolean isSynthetic()
        {
        return (m_nFlags & SYNTHETIC_BIT) != 0;
        }

    /**
     * Specify whether or not the component is synthetic.
     *
     * @param fSynthetic  true to specify the component is synthetic; false otherwise
     */
    public void setSynthetic(boolean fSynthetic)
        {
        int nFlagsOld = m_nFlags;
        int nFlagsNew = (nFlagsOld & ~SYNTHETIC_BIT) | (fSynthetic ? SYNTHETIC_BIT : 0);
        if (nFlagsNew != nFlagsOld)
            {
            m_nFlags = (short) nFlagsNew;
            markModified();
            }
        }

    /**
     * Obtain the name of the component. All components have a name, although the purpose of the
     * name varies slightly for several components:
     * <ul>
     * <li>The Package, various Class types (Interface, Class, Const, Enum, Mixin, Trait, and
     *     Service), Property, and MultiMethod all are identified within their parent component by
     *     their name, using a NamedConstant;</li>
     * <li>The Method has a name, but it is identified by its signature, because it is a child of a
     *     MultiMethod component;</li>
     * <li>The Module has a name, but it is a full qualified name (composed of a simple name and a
     *     domain name); and</li>
     * <li>The File has a name, which defaults to the module's simple name plus the ".xtc"
     *     extension (if the actual file name is not known).</li>
     * </ul>
     * @return
     */
    public String getName()
        {
        return ((NamedConstant) getIdentityConstant()).getName();
        }

    /**
     * @return true if the identity of this component is visible from anywhere
     */
    public boolean isGloballyVisible()
        {
        Component parent = getParent();
        return parent.isGloballyVisible() && !parent.isChildLessVisible();
        }

    /**
     * @return true if the identity of a child component is less visible than the identity of this
     *         component; this tends to be true when this component is a method
     */
    protected boolean isChildLessVisible()
        {
        return false;
        }

    /**
     * @return assuming that this is one of any number of siblings, obtain a reference to the first
     *         sibling (which may be this); never null
     */
    protected Component getEldestSibling()
        {
        Component parent = getParent();
        if (parent == null)
            {
            return this;
            }

        Component sibling = parent.getChildByNameMap().get(getName());
        assert sibling != null;
        return sibling;
        }

    /**
     * @return assuming that this is one of any number of siblings, obtain a reference to the next
     *         sibling, which may be null to indicate no more siblings
     */
    protected Component getNextSibling()
        {
        return m_sibling;
        }

    /**
     * Iterate all of the siblings of this component, including this component.
     *
     * @return an Iterator of sibling components, including this component
     */
    protected Iterator<Component> siblings()
        {
        return new Iterator<Component>()
            {
            private Component nextSibling = getEldestSibling();

            @Override
            public boolean hasNext()
                {
                return nextSibling != null;
                }

            @Override
            public Component next()
                {
                Component sibling = nextSibling;
                if (sibling == null)
                    {
                    throw new NoSuchElementException();
                    }

                nextSibling = sibling.m_sibling;
                return sibling;
                }
            };
        }

    /**
     * Obtain a read-only map of all children identified by name.
     * <p/>
     * Note: the returned map does not contain any of the child methods.
     *
     * @return a read-only map from name to child component; never null, even if there are no
     *         children
     */
    protected Map<String, Component> getChildByNameMap()
        {
        ensureChildren();
        Map<String, Component> map = m_childByName;
        return map == null ? Collections.EMPTY_MAP : map;
        }

    /**
     * Obtain the actual read/write map of all children that are identified by name.
     * <p/>
     * Note: the returned map does not contain any of the child methods.
     *
     * @return obtain the actual map from name to child component, creating the map if necessary
     */
    protected Map<String, Component> ensureChildByNameMap()
        {
        ensureChildren();
        Map<String, Component> map = m_childByName;
        if (map == null)
            {
            map = new HashMap<>(7);

            // store the map on every one of the siblings (including this component)
            for (Iterator<Component> siblings = siblings(); siblings.hasNext(); )
                {
                siblings.next().m_childByName = map;
                }

            // the corresponding field on this component should now be initialized
            assert m_childByName == map;
            }
        return map;
        }

    /**
     * Obtain a read-only map of all method children identified by method signature constant.
     * <p/>
     * Note: the returned map contains only methods
     *
     * @return a read-only map from method constant to method component; never null, even if there
     *         are no child methods
     */
    protected Map<MethodConstant, MethodStructure> getMethodByConstantMap()
        {
        ensureChildren();
        Map<MethodConstant, MethodStructure> map = m_methodByConstant;
        return map == null ? Collections.EMPTY_MAP : map;
        }

    /**
     * Obtain the actual read/write map of all method children identified by method signature
     * constant.
     * <p/>
     * Note: the returned map contains only methods
     *
     * @return obtain the actual map from method constant to method component, creating the map if
     *         necessary
     */
    protected Map<MethodConstant, MethodStructure> ensureMethodByConstantMap()
        {
        ensureChildren();
        Map<MethodConstant, MethodStructure> map = m_methodByConstant;
        if (map == null)
            {
            map = new HashMap<>(7);

            // store the map on every one of the siblings (including this component)
            for (Iterator<Component> siblings = siblings(); siblings.hasNext(); )
                {
                siblings.next().m_methodByConstant = map;
                }

            // the corresponding field on this component should now be initialized
            assert m_methodByConstant == map;
            }
        return map;
        }

    /**
     * Make sure that any deferred child deserialization is complete
     */
    private void ensureChildren()
        {
        if (m_abChildren != null)
            {
            // first grab the deferred deserialization bytes and then make sure neither this nor any
            // sibling retains hold of it (since it indicates that deserialization is deferred)
            byte[] ab = m_abChildren;
            for (Iterator<Component> siblings = siblings(); siblings.hasNext(); )
                {
                siblings.next().m_abChildren = null;
                }

            // now read in the children
            DataInput in = new DataInputStream(new ByteArrayInputStream(ab));
            try
                {
                disassembleChildren(in, true);
                }
            catch (IOException e)
                {
                throw new IllegalStateException("IOException occurred in " + getIdentityConstant()
                        + " during deferred read of child components", e);
                }
            }
        }

    /**
     * Visitor pattern for children of this component, optionally including all siblings, and
     * optionally recursively through the remainder of the component hierarchy.
     *
     * @param visitor     the consumer to use as the visitor, passing each child component
     * @param fSiblings   true to visit all siblings; false to visit only the eldest sibling
     * @param fRecursive  true to recursively visit the children of the children, and so on
     */
    protected void visitChildren(Consumer<Component> visitor, boolean fSiblings, boolean fRecursive)
        {
        for (Component component : getChildByNameMap().values())
            {
            Component componentEldest = component;

            do
                {
                visitor.accept(component);
                component = component.getNextSibling();
                }
            while (fSiblings && component != null);

            if (fRecursive)
                {
                componentEldest.visitChildren(visitor, fSiblings, fRecursive);
                }
            }

        for (Component component : getMethodByConstantMap().values())
            {
            Component componentEldest = component;

            do
                {
                visitor.accept(component);
                component = component.getNextSibling();
                }
            while (fSiblings && component != null);

            if (fRecursive)
                {
                componentEldest.visitChildren(visitor, fSiblings, fRecursive);
                }
            }
        }

    /**
     * Adopt the specified child.
     *
     * @param child  the new child of this component
     */
    protected void addChild(Component child)
        {
        ensureChildren();

        // if the child is a method, it will be stored by its signature; otherwise, it is stored by
        // its name
        Map<Object, Component> kids;
        Object id;
        if (child instanceof MethodStructure)
            {
            kids = (Map) ensureMethodByConstantMap();
            id   = child.getIdentityConstant();
            }
        else
            {
            kids = (Map) ensureChildByNameMap();
            id   = child.getName();
            }

        Component sibling = kids.get(id);
        if (sibling == null)
            {
            kids.put(id, child);
            }
        else
            {
            // there has to be a condition that sets the new kid apart from its siblings, but that
            // condition might not be available (resolved) when the kid is created, so defer the
            // check for the existence of the condition and the mutual exclusivity of the condition
            // until much later in the assembly
            // if (child.m_cond == null)
            //     {
            //     throw new IllegalStateException("cannot add child with same ID (" + id
            //             + ") if condition == null");
            //     }
            // if (sibling.m_cond == null)
            //     {
            //     throw new IllegalStateException("cannot add child if sibling with same ID (" + id
            //             + ") has condition == null");
            //     }

            // make sure that the parent is set correctly
            child.setContaining(this);

            // the new kid gets put at the end of the linked list of siblings
            Component lastSibling = sibling;
            while (lastSibling.m_sibling != null)
                {
                lastSibling = lastSibling.m_sibling;
                }
            lastSibling.m_sibling = child;

            // the child can't have any of its own children; that "merge" functionality is simply
            // not supported by this operation
            assert child.m_abChildren       == null;
            assert child.m_childByName      == null;
            assert child.m_methodByConstant == null;

            // make sure that the various sibling-shared fields are configured
            child.m_abChildren       = sibling.m_abChildren;
            child.m_childByName      = sibling.m_childByName;
            child.m_methodByConstant = sibling.m_methodByConstant;
            }

        markModified();
        }

    /**
     * @return true if this component can contain packages
     */
    public boolean isPackageContainer()
        {
        return false;
        }

    /**
     * Create and register a PackageStructure with the specified package name.
     *
     * @param access  the accessibility of the package to create
     * @param sName   the simple (unqualified) package name to create
     */
    public PackageStructure createPackage(Access access, String sName)
        {
        assert sName != null;
        assert access != null;

        if (!isPackageContainer())
            {
            throw new IllegalStateException("this (" + this + ") cannot contain a package");
            }

        // the check for duplicates is deferred, since it is possible (thanks to the complexity of
        // conditionals) to have multiple components occupying the same location within the
        // namespace at this point in the compilation
        // Component component = getChild(sName);
        // if (component != null)
        //     {
        //     throw new IllegalStateException("cannot add a package \"" + sName
        //             + "\" because a child with that name already exists: " + component);
        //     }

        int              nFlags  = Format.PACKAGE.ordinal() | access.FLAGS;
        PackageConstant  constId = getConstantPool().ensurePackageConstant(getIdentityConstant(), sName);
        PackageStructure struct  = new PackageStructure(this, nFlags, constId, null);
        addChild(struct);

        return struct;
        }

    /**
     * @return true if this component can contain classes and properties
     */
    public boolean isClassContainer()
        {
        return false;
        }

    /**
     * Create and register a ClassStructure with the specified class name.
     *
     * @param access  the accessibility of the class to create
     * @param format  the category format of the class
     * @param sName   the simple (unqualified) class name to create
     */
    public ClassStructure createClass(Access access, Format format, String sName)
        {
        assert sName != null;
        assert access != null;

        if (!isClassContainer())
            {
            throw new IllegalStateException("this (" + this + ") cannot contain a class");
            }

        // the check for duplicates is deferred, since it is possible (thanks to the complexity of
        // conditionals) to have multiple components occupying the same location within the
        // namespace at this point in the compilation
        // Component component = getChild(sName);
        // if (component != null)
        //     {
        //     throw new IllegalStateException("cannot add a class \"" + sName
        //             + "\" because a child with that name already exists: " + component);
        //     }

        int            nFlags  = format.ordinal() | access.FLAGS;
        ClassConstant  constId = getConstantPool().ensureClassConstant(getIdentityConstant(), sName);
        ClassStructure struct  = new ClassStructure(this, nFlags, constId, null);
        addChild(struct);

        return struct;
        }

    /**
     * Create and register a PropertyStructure with the specified name.
     *
     * @param fStatic    true if the property is marked as static
     * @param access     the accessibility of the class to create
     * @param constType  the category format of the class
     * @param sName      the simple (unqualified) class name to create
     */
    public PropertyStructure createProperty(boolean fStatic, Access access, TypeConstant constType, String sName)
        {
        assert sName != null;
        assert access != null;
        assert constType != null;

        if (!isClassContainer())
            {
            throw new IllegalStateException("this (" + this + ") cannot contain a property");
            }

        // the check for duplicates is deferred, since it is possible (thanks to the complexity of
        // conditionals) to have multiple components occupying the same location within the
        // namespace at this point in the compilation
        // Component component = getChild(sName);
        // if (component != null)
        //     {
        //     throw new IllegalStateException("cannot add a class \"" + sName
        //             + "\" because a child with that name already exists: " + component);
        //     }

        int               nFlags  = Format.PROPERTY.ordinal() | access.FLAGS;
        PropertyConstant  constId = getConstantPool().ensurePropertyConstant(getIdentityConstant(), sName);
        PropertyStructure struct  = new PropertyStructure(this, nFlags, constId, null);
        struct.setType(constType);
        addChild(struct);

        return struct;
        }

    /**
     * @return true if this component can contain multi-methods
     */
    public boolean isMethodContainer()
        {
        return false;
        }

    /**
     * Create a MethodStructure with the specified name, but whose identity may not yet be fully
     * realized / resolved.
     *
     * @param fFunction    true if the method is actually a function (not a method)
     * @param access       the access flag for the method
     * @param returnTypes  the return types of the method
     * @param sName        the method name, or null if the name is unknown
     * @param paramTypes   the parameter types for the method
     *
     * @return a new MethodStructure
     */
    public MethodStructure createMethod(boolean fFunction, Access access,
            TypeConstant[] returnTypes, String sName, TypeConstant[] paramTypes)
        {
        assert sName != null;
        assert access != null;

        MultiMethodStructure multimethod = ensureMultiMethodStructure(sName);
        return multimethod.createMethod(fFunction, access, returnTypes, paramTypes);
        }

    public MultiMethodStructure ensureMultiMethodStructure(String sName)
        {
        Component sibling = getChildByNameMap().get(sName);
        while (sibling != null)
            {
            if (sibling instanceof MultiMethodStructure)
                {
                return (MultiMethodStructure) sibling;
                }

            sibling = sibling.m_sibling;
            }

        MultiMethodConstant  constId = getConstantPool().ensureMultiMethodConstant(getIdentityConstant(), sName);
        MultiMethodStructure struct  = new MultiMethodStructure(this, Format.MULTIMETHOD.ordinal(), constId, null);
        addChild(struct);

        return struct;
        }

    /**
     * Add the specified version as a condition on this component.
     *
     * @param ver  the version
     */
    protected void addVersion(Version ver)
        {
        ConditionalConstant cond = getCondition();
        if (cond == null)
            {
            setCondition(getConstantPool().ensureVersionedCondition(ver));
            }
        else
            {
            setCondition(cond.addVersion(ver));
            }
        }

    /**
     * Remove the specified version as a condition from this component.
     *
     * @param ver  the version
     */
    protected void removeVersion(Version ver)
        {
        ConditionalConstant cond = getCondition();
        if (cond != null)
            {
            setCondition(cond.removeVersion(ver));
            }
        }

    /**
     * Modify the condition on this component by adding another required condition.
     *
     * @param cond  the condition to require
     */
    protected void addAndCondition(ConditionalConstant cond)
        {
        if (cond != null)
            {
            ConditionalConstant condOld = m_cond;
            m_cond = condOld == null ? cond : condOld.addAnd(cond);
            markModified();
            }
        }

    /**
     * Modify the condition on this component by adding an alternative condition.
     *
     * @param cond  the alternative condition
     */
    protected void addOrCondition(ConditionalConstant cond)
        {
        if (cond != null)
            {
            ConditionalConstant condOld = m_cond;
            m_cond = condOld == null ? cond : condOld.addOr(cond);
            markModified();
            }
        }

    /**
     * Without comparing the child components, compare this component to another component to
     * determine if their state is identical. This method must be overridden by components that
     * have state in addition to that represented by the identity constant and the component's
     * bit flags.
     *
     * @param that  another component to compare to
     *
     * @return true iff this component's "body" is identical to that component's "body"
     */
    protected boolean isBodyIdentical(Component that)
        {
        return this.m_nFlags == that.m_nFlags
            && this.m_constId.equals(that.m_constId);
        }

    /**
     * Comparing only the child components (recursively), determine if this component's children
     * are identical to that component's children.
     *
     * @param that
     *
     * @return
     */
    protected boolean areChildrenIdentical(Component that)
        {
        ensureChildren();
        return  equalChildMaps(this.getChildByNameMap(), that.getChildByNameMap()) &&
                equalChildMaps(this.getMethodByConstantMap(), that.getMethodByConstantMap());
        }

    private boolean equalChildMaps(Map<? extends Object, ? extends Component> mapThis,
                                   Map<? extends Object, ? extends Component> mapThat)
        {
        if (mapThis.size() != mapThat.size())
            {
            return false;
            }

        if (mapThis.isEmpty())
            {
            return true;
            }

        for (Object key : mapThis.keySet())
            {
            Component childThis = mapThis.get(key);
            Component childThat = mapThat.get(key);

            for (Component eachThis = childThis, eachThat = childThat;
                    eachThis != null || eachThat != null;
                    eachThis = eachThis.m_sibling, eachThat = eachThat.m_sibling)
                {
                if (eachThis == null || eachThat == null)
                    {
                    return false;
                    }
                }
            if (childThat == null)
                {
                return false;
                }
            }

        return true;
        }

    /**
     * Obtain the child that is identified by the specified identity. If more than one child is
     * a match, then a component representing the multiple siblings is created to represent the
     * result.
     *
     * @param constId  the constant identifying the child
     *
     * @return the child component, or null
     */
    public Component getChild(Constant constId)
        {
        Component firstSibling = constId instanceof MethodConstant
                ? getMethodByConstantMap().get(constId)
                : getChildByNameMap().get(((NamedConstant) constId).getName());

        // common result: nothing for that constant
        if (firstSibling == null)
            {
            return null;
            }

        // common result: exactly one non-conditional match
        if (firstSibling.m_sibling == null
                && firstSibling.getIdentityConstant().equals(constId)
                && firstSibling.m_cond == null)
            {
            return firstSibling;
            }

        List<Component>  matches = selectMatchingSiblings(firstSibling);
        if (matches.isEmpty())
            {
            return null;
            }

        if (matches.size() == 1)
            {
            return matches.get(0);
            }

        return new CompositeComponent(this, matches);
        }

    /**
     * For all but the multi-method, this obtains a child by the specified name. (Unlike all other
     * components, the multi-method identifies its children by method signature information.)
     *
     * @param sName  the child name
     *
     * @return the child component
     */
    public Component getChild(String sName)
        {
        // there are five cases:
        // 1) no child by that name - return null
        // 2) one unconditional child by that name - return the child
        // 3) a number of children by that name, but no conditions match - return null
        // 4) a number of children by that name, one condition matches - return that child
        // 5) a number of children by that name, multiple conditions match - return a composite child

        // most common result: no child by that name
        Component firstSibling = getChildByNameMap().get(sName);
        if (firstSibling == null)
            {
            return null;
            }

        // common result: exactly one non-conditional match
        if (firstSibling.m_sibling == null && firstSibling.m_cond == null)
            {
            return firstSibling;
            }

        List<Component>  matches = selectMatchingSiblings(firstSibling);
        if (matches.isEmpty())
            {
            return null;
            }

        if (matches.size() == 1)
            {
            return matches.get(0);
            }

        return new CompositeComponent(this, matches);
        }

    /**
     * Obtain a list of the child components contained within this Component. Note that this is a
     * fairly expensive operation, because each potential child must be evaluated for inclusion as
     * if it were requested explicitly via {@link #getChild(String)} or {@link #getChild(Constant)}.
     *
     * @return a list of the component's children
     */
    public List<Component> children()
        {
        List<Component> list = new ArrayList<>();

        for (String sName : getChildByNameMap().keySet())
            {
            Component child = getChild(sName);
            if (child != null)
                {
                list.add(child);
                }
            }

        for (MethodConstant constId : getMethodByConstantMap().keySet())
            {
            Component child = getChild(constId);
            if (child != null)
                {
                list.add(child);
                }
            }

        return list;
        }

    protected List<Component> selectMatchingSiblings(Component firstSibling)
        {
        AssemblerContext ctxAsm  = getFileStructure().getContext();
        LinkerContext    ctxLink = ctxAsm == null ? null : ctxAsm.getLinkerContext();
        List<Component>  matches = null;

        // see which siblings will be present based on what has been required in the current
        // assembler context
        for (Component eachSibling = firstSibling; eachSibling != null; eachSibling = eachSibling.m_sibling)
            {
            if (ctxLink == null || eachSibling.isPresent(ctxLink))
                {
                if (matches == null)
                    {
                    matches = new ArrayList<>();
                    }
                matches.add(eachSibling);
                }
            }

        return matches == null ? Collections.EMPTY_LIST : matches;
        }

    /**
     * Read zero or more child components from the DataInput stream. For a given identity, there may
     * be more than one child component if the child components are conditional. For all components
     * (except for methods i.e. within multi-methods), the children are identified by name.
     *
     * @param in     the DataInput containing the components
     * @param fLazy  true to defer the child deserialization until necessary
     *
     * @throws IOException  if an I/O exception occurs during disassembly from the provided
     *                      DataInput stream, or if there is invalid data in the stream
     */
    protected void disassembleChildren(DataInput in, boolean fLazy)
            throws IOException
        {
        // read component children
        int cKids = readMagnitude(in);
        while (cKids-- > 0)
            {
            ConstantPool pool = getConstantPool();
            Component    kid  = null;

            // read component body (or bodies)
            int n = in.readUnsignedByte();
            if ((n & CONDITIONAL_BIT) == 0)
                {
                // there isn't a conditional multiple-component list, so this is just the first byte of
                // the two-byte FLAGS value (which is the start of the body) for a single component
                n = (n << 8) | in.readUnsignedByte();
                kid = Format.fromFlags(n).instantiate(this, pool.getConstant(readMagnitude(in)), n, null);
                kid.disassemble(in);
                }
            else
                {
                // some number of components, each with a condition
                Component    prevSibling = null;
                int          cSiblings   = readMagnitude(in);
                assert cSiblings > 0;
                for (int i = 0; i < cSiblings; ++i)
                    {
                    ConditionalConstant condition  = (ConditionalConstant) pool.getConstant(readIndex(in));
                    int                 nFlags     = in.readUnsignedShort();
                    Component           curSibling = Format.fromFlags(nFlags).instantiate(
                            this, pool.getConstant(readMagnitude(in)), nFlags, condition);

                    // the remainder of the body of the current sibling is at the current point of
                    // the stream (but do NOT disassemble the children)
                    curSibling.disassemble(in);

                    if (prevSibling == null)
                        {
                        kid = curSibling;
                        }
                    else
                        {
                        prevSibling.m_sibling = curSibling;
                        }
                    prevSibling = curSibling;
                    }
                }

            // register the eldest sibling in the namespace of this component; this has to be done
            // before recursing to the children for disassembly so that the parent/child refs are
            // already in place, just in case a child asks e.g. for its eldest sibling
            addChild(kid);

            int cb = readMagnitude(in);
            if (cb > 0)
                {
                if (fLazy)
                    {
                    // just read the bytes for the children and store it off for later
                    byte[] ab = new byte[cb];
                    in.readFully(ab);
                    for (Component eachSibling = kid; eachSibling != null; eachSibling = eachSibling.m_sibling)
                        {
                        // note that every sibling has a copy of all of the children; this is because
                        // the byte[] serves as both the storage of those children and an indicator that
                        // the deserialization of the children has been deferred
                        eachSibling.m_abChildren = ab;
                        }
                    }
                else
                    {
                    kid.disassembleChildren(in, fLazy);
                    }
                }
            }
        }

    /**
     * Register all constants used by the child components.
     *
     * @param pool  the ConstantPool with which to register each constant referenced by the child
     *              components
     */
    protected void registerChildrenConstants(ConstantPool pool)
        {
        for (Component child : getChildByNameMap().values())
            {
            registerChildConstants(pool, child);
            }

        for (Component child : getMethodByConstantMap().values())
            {
            registerChildConstants(pool, child);
            }
        }

    /**
     * Register the constants for a child AND all of its siblings, and then recursively for the
     * various children of those siblings.
     *
     * @param pool   the constant pool
     * @param child  the eldest sibling of the siblings to recursively register the constants for
     */
    private void registerChildConstants(ConstantPool pool, Component child)
        {
        for (Component eachSibling = child; eachSibling != null; eachSibling = eachSibling.m_sibling)
            {
            eachSibling.registerConstants(pool);
            }

        // now register the grand-children (the children of the various siblings we just iterated)
        child.registerChildrenConstants(pool);
        }

    /**
     * Write any child components to the DataOutput stream.
     *
     * @param out  the DataOutput to write the child components to
     *
     * @throws IOException  if an I/O exception occurs during assembly to the provided DataOutput
     *                      stream
     */
    protected void assembleChildren(DataOutput out)
            throws IOException
        {
        int cKids = getChildByNameMap().size() + getMethodByConstantMap().size();
        writePackedLong(out, cKids);

        if (cKids > 0)
            {
            int cActual = 0;

            for (Component child : getChildByNameMap().values())
                {
                assembleChild(out, child);
                ++cActual;
                }

            for (Component child : getMethodByConstantMap().values())
                {
                assembleChild(out, child);
                ++cActual;
                }

            assert cActual == cKids;
            }
        }

    /**
     * Write a child AND all of its siblings to the DataOutput stream, and then recursively for the
     * various children of those siblings.
     *
     * @param out    the DataOutput to write the child components to
     * @param child  the eldest sibling of the siblings to recursively assemble
     *
     * @throws IOException  if an I/O exception occurs during assembly to the provided DataOutput
     *                      stream
     */
    private void assembleChild(DataOutput out, Component child)
            throws IOException
        {
        if (child.m_sibling != null || child.m_cond != null)
            {
            // multiple child / conditional format:
            // first is an indicator that we're using the conditional format
            out.writeByte(CONDITIONAL_BIT);

            // second is the number of kids
            int cSiblings = 0;
            for (Component eachSibling = child; eachSibling != null; eachSibling = eachSibling.m_sibling)
                {
                ++cSiblings;
                }
            writePackedLong(out, cSiblings);

            // last comes a sequence of siblings, each preceded by its condition
            for (Component eachSibling = child; eachSibling != null; eachSibling = eachSibling.m_sibling)
                {
                writePackedLong(out, Constant.indexOf(eachSibling.m_cond));
                eachSibling.assemble(out);
                }
            }
        else
            {
            // single child format
            child.assemble(out);
            }

        // children nested under these siblings are length-encoded as a group
        if (child.getChildByNameMap().isEmpty() && child.getMethodByConstantMap().isEmpty())
            {
            writePackedLong(out, 0);
            }
        else
            {
            ByteArrayOutputStream outNestedRaw = new ByteArrayOutputStream();
            DataOutputStream outNestedData = new DataOutputStream(outNestedRaw);
            child.assembleChildren(outNestedData);
            byte[] abGrandChildren = outNestedRaw.toByteArray();
            writePackedLong(out, abGrandChildren.length);
            out.write(abGrandChildren);
            }
        }

    /**
     * Dump all the children of this component, recursively.
     *
     * @param out      the PrintWriter to dump to
     * @param sIndent  the indentation to use for this level
     */
    protected void dumpChildren(PrintWriter out, String sIndent)
        {
        // go through each named and constant-identified child, and dump it, and its siblings
        for (Component child : getChildByNameMap().values())
            {
            dumpChild(child, out, sIndent);
            }
        for (Component child : getMethodByConstantMap().values())
            {
            dumpChild(child, out, sIndent);
            }
        }

    /**
     * Dump a child and all of its siblings, and then its children under it.
     *
     * @param child    a child (an eldest sibling)
     * @param out      the PrintWriter to dump to
     * @param sIndent  the indentation to use for this level
     */
    private void dumpChild(Component child, PrintWriter out, String sIndent)
        {
        // dump all of the siblings
        for (Component eachSibling = child; eachSibling != null; eachSibling = eachSibling.m_sibling)
            {
            eachSibling.dump(out, sIndent);
            }

        // dump the shared children
        child.dumpChildren(out, nextIndent(sIndent));
        }


    // ----- Documentable methods ------------------------------------------------------------------

    @Override
    public String getDocumentation()
        {
        return m_sDoc;
        }

    @Override
    public void setDocumentation(String sDoc)
        {
        m_sDoc = sDoc;
        markModified();
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    public Iterator<? extends XvmStructure> getContained()
        {
        Iterator<? extends XvmStructure> iter = null;

        ensureChildren();
        if (m_childByName != null)
            {
            iter = m_childByName.values().iterator();
            }

        if (m_methodByConstant != null)
            {
            Iterator<? extends XvmStructure> iter2 = m_methodByConstant.values().iterator();
            iter = iter == null ? iter2 : new LinkedIterator(iter, iter2);
            }

        return iter == null ? Collections.emptyIterator() : iter;
        }

    @Override
    public boolean isModified()
        {
        for (Component eachSibling = this; eachSibling != null; eachSibling = eachSibling.m_sibling)
            {
            if (eachSibling.isBodyModified())
                {
                return true;
                }
            }
        return super.isModified();
        }

    protected boolean isBodyModified()
        {
        return m_fModified;
        }

    @Override
    protected void markModified()
        {
        m_fModified = true;
        }

    @Override
    protected void resetModified()
        {
        for (Component eachSibling = this; eachSibling != null; eachSibling = eachSibling.m_sibling)
            {
            eachSibling.m_fModified = false;
            }
        super.resetModified();
        }

    @Override
    public String getDescription()
        {
        return "name=" + getName() + ", format=" + getFormat() + ", access=" + getAccess()
                + ", abstract=" + isAbstract()+ ", static=" + isStatic()
                + ", synthetic=" + isSynthetic() + ", next-sibling=" + (m_sibling != null)
                + ", modified=" + m_fModified;
        }

    /**
     * {@inheritDoc}
     * <p/>
     * For all but the FileStructure Component, this method applies only to the body of the
     * component and not to its children.
     *
     * @see #disassembleChildren(DataInput, boolean)
     */
    @Override
    protected void disassemble(DataInput in)
            throws IOException
        {
        assert getContaining() == null || getContaining() instanceof Component;
        // there's nothing to do here, since the disassembly of the common body parts that all
        // Component's share is done by the readComponent() and Format.instantiate() methods
        }

    /**
     * {@inheritDoc}
     * <p/>
     * For all but the FileStructure Component, this method applies only to the body of the
     * component and not to its children.
     *
     * @see #registerChildrenConstants(ConstantPool)
     */
    @Override
    protected void registerConstants(ConstantPool pool)
        {
        assert getContaining() == null || getContaining() instanceof Component;

        m_constId = (IdentityConstant   ) pool.register(m_constId);
        m_cond    = (ConditionalConstant) pool.register(m_cond);
        }

    /**
     * {@inheritDoc}
     * <p/>
     * For all but the FileStructure Component, this method applies only to the body of the
     * component and not to its children.
     *
     * @see #assembleChildren(DataOutput)
     */
    @Override
    protected void assemble(DataOutput out)
            throws IOException
        {
        assert getContaining() == null || getContaining() instanceof Component;

        out.writeShort(m_nFlags);
        writePackedLong(out, m_constId.getPosition());
        }

    @Override
    public ConditionalConstant getCondition()
        {
        return m_cond;
        }

    @Override
    protected void setCondition(ConditionalConstant condition)
        {
        m_cond = condition;
        markModified();
        }

    /**
     * {@inheritDoc}
     * <p/>
     * For all but the FileStructure Component, this method applies only to the body of the
     * component and not to its children.
     *
     * @see #dumpChildren(PrintWriter, String)
     */
    @Override
    protected void dump(PrintWriter out, String sIndent)
        {
        out.print(sIndent);
        out.println(toString());
        }


    // ----- Object methods ------------------------------------------------------------------------

    @Override
    public boolean equals(Object obj)
        {
        if (obj instanceof Component)
            {
            Component that = (Component) obj;
            return isBodyIdentical(that) && areChildrenIdentical(that);
            }

        return false;
        }

    @Override
    public int hashCode()
        {
        return getIdentityConstant().hashCode();
        }


    // ----- inner class: Format -------------------------------------------------------------------

    /**
     * The Format enumeration defines the multiple different binary formats used to store component
     * information.
     * <p/>
     * Those beginning with "RSVD_" are reserved, and must not be used.
     */
    public enum Format
        {
        INTERFACE,
        CLASS,
        CONST,
        ENUM,
        ENUMVALUE,
        MIXIN,
        TRAIT,
        SERVICE,
        PACKAGE,
        MODULE,
        PROPERTY,
        METHOD,
        RSVD_C,
        RSVD_D,
        MULTIMETHOD,
        FILE;

        /**
         * Determine the format from a component's bit-flags value.
         *
         * @param nFlags  the 2-byte component bit-flags value
         *
         * @return the Format specified by the bit flags
         */
        static Format fromFlags(int nFlags)
            {
            return valueOf((nFlags & FORMAT_MASK) >>> FORMAT_SHIFT);
            }

        /**
         * Instantiate a component as it is being read from a stream, reading its body (but NOT its
         * children).
         *
         * @param xsParent   the parent component
         * @param constId    the constant for the new component's identity
         * @param nFlags     the flags that define the common attributes of the component
         * @param condition  the condition under which the component is present, or null
         *
         * @return the component
         *
         * @throws IOException if something goes wrong reading from the stream
         */
        Component instantiate(XvmStructure xsParent, Constant constId, int nFlags, ConditionalConstant condition)
            {
            if (xsParent == null)
                {
                throw new IllegalStateException("parent required");
                }

            switch (this)
                {
                case FILE:
                    throw new IllegalStateException("file is not instantiable");

                case MODULE:
                    return new ModuleStructure(xsParent, nFlags, (ModuleConstant) constId, condition);

                case PACKAGE:
                    return new PackageStructure(xsParent, nFlags, (PackageConstant) constId, condition);

                case INTERFACE:
                case CLASS:
                case CONST:
                case ENUM:
                case ENUMVALUE:
                case MIXIN:
                case TRAIT:
                case SERVICE:
                    return new ClassStructure(xsParent, nFlags, (ClassConstant) constId, condition);

                case PROPERTY:
                    return new PropertyStructure(xsParent, nFlags, (PropertyConstant) constId, condition);

                case MULTIMETHOD:
                    return new MultiMethodStructure(xsParent, nFlags, (MultiMethodConstant) constId, condition);

                case METHOD:
                    return new MethodStructure(xsParent, nFlags, (MethodConstant) constId, condition);

                default:
                    throw new IllegalStateException("uninstantiable format: " + this);
                }
            }

        public boolean isImplicitlyStatic()
            {
            switch (this)
                {
                case MODULE:
                case PACKAGE:
                case ENUM:
                case ENUMVALUE:
                    return true;

                case INTERFACE:
                case CLASS:
                case CONST:
                case MIXIN:
                case TRAIT:
                case SERVICE:
                case PROPERTY:
                case MULTIMETHOD:
                case METHOD:
                    return false;

                default:
                    throw new IllegalStateException("unsupported format: " + this);
                }
            }

        /**
         * Look up a Format enum by its ordinal.
         *
         * @param i  the ordinal
         *
         * @return the Format enum for the specified ordinal
         */
        public static Format valueOf(int i)
            {
            return FORMATS[i];
            }

        /**
         * All of the Format enums.
         */
        private static final Format[] FORMATS = Format.values();
        }


    // ----- helpers -------------------------------------------------------------------------------

    /**
     * Helper method to read a collection of type parameters.
     *
     * @param in  the DataInput containing the type parameters
     *
     * @return null if there are no type parameters, otherwise a map from CharStringConstant to the
     *         type constraint for each parameter
     *
     * @throws IOException  if an I/O exception occurs during disassembly from the provided
     *                      DataInput stream, or if there is invalid data in the stream
     */
    protected ListMap<CharStringConstant, TypeConstant> disassembleTypeParams(DataInput in)
            throws IOException
        {
        int c = readMagnitude(in);
        if (c <= 0)
            {
            assert c == 0;
            return null;
            }

        final ListMap<CharStringConstant, TypeConstant> map = new ListMap<>();
        final ConstantPool pool = getConstantPool();
        for (int i = 0; i < c; ++i)
            {
            CharStringConstant constName = (CharStringConstant) pool.getConstant(readIndex(in));
            TypeConstant       constType = (TypeConstant)       pool.getConstant(readIndex(in));
            assert !map.containsKey(constName);
            map.put(constName, constType);
            }
        return map;
        }

    /**
     * Register all of the constants associated with a list of type parameters.
     *
     * @param mapOld  the map containing the type parameters
     *
     * @return the map of registered type parameters (might be different from the map passed in)
     */
    protected ListMap<CharStringConstant, TypeConstant> registerTypeParams(ListMap<CharStringConstant, TypeConstant> mapOld)
        {
        if (mapOld == null || mapOld.isEmpty())
            {
            return mapOld;
            }

        final ConstantPool pool = getConstantPool();
        ListMap<CharStringConstant, TypeConstant> mapNew = mapOld;
        for (Map.Entry<CharStringConstant, TypeConstant> entry : mapOld.entrySet())
            {
            CharStringConstant constOldKey = entry.getKey();
            CharStringConstant constNewKey = (CharStringConstant) pool.register(constOldKey);

            TypeConstant       constOldVal = entry.getValue();
            TypeConstant       constNewVal = (TypeConstant) pool.register(constOldVal);

            if (mapNew != mapOld || constOldKey != constNewKey)
                {
                if (mapNew == mapOld)
                    {
                    // up to this point, we've been using the old map, but now we need to change a
                    // key (which map does not support), so create a new map, and copy the old map
                    // to the new map, but only up to (but not including!) the current entry
                    mapNew = new ListMap<>();
                    for (Map.Entry<CharStringConstant, TypeConstant> entryCopy : mapOld.entrySet())
                        {
                        if (entryCopy.getKey() == constOldKey)
                            {
                            break;
                            }

                        mapNew.put(entryCopy.getKey(), entryCopy.getValue());
                        }
                    }

                mapNew.put(constNewKey, constNewVal);
                }
            else if (constOldVal != constNewVal)
                {
                entry.setValue(constNewVal);
                }
            }
        return mapNew;
        }

    /**
     * Helper method to write type parameters to the DataOutput stream.
     *
     * @param map  the type parameters
     * @param out  the DataOutput to write the XVM structure to
     *
     * @throws IOException  if an I/O exception occurs during assembly to the provided DataOutput
     *                      stream
     */
    protected void assembleTypeParams(ListMap<CharStringConstant, TypeConstant> map, DataOutput out)
            throws IOException
        {
        int c = map == null ? 0 : map.size();
        writePackedLong(out, c);

        if (c == 0)
            {
            return;
            }

        for (Map.Entry<CharStringConstant, TypeConstant> entry : map.entrySet())
            {
            writePackedLong(out, entry.getKey().getPosition());
            writePackedLong(out, entry.getValue().getPosition());
            }
        }


    // ----- constants -----------------------------------------------------------------------------

    /**
     * If the leading byte of the flags contains a conditional bit, then it isn't actually the
     * leading byte of the flags, and instead is an indicator that the conditional format is being
     * used, possibly with more than one component of the same name. Specifically, if that leading
     * byte has the CONDITIONAL_BIT set, then that byte is followed by a packed integer specifying
     * the number of components of the same name, and for each component there is a packed integer
     * for the conditional constant ID followed by the body of the component. (The children that go
     * with the various conditional components occur in the stream after the <b>last</b> body.)
     */
    public static final int CONDITIONAL_BIT =   0x80;

    public static final int FORMAT_MASK     = 0x000F, FORMAT_SHIFT    = 0;
    public static final int ACCESS_MASK     = 0x0300, ACCESS_SHIFT    = 8;
    public static final int ACCESS_PUBLIC   = 0x0100;
    public static final int ACCESS_PROTECTED= 0x0200;
    public static final int ACCESS_PRIVATE  = 0x0300;
    public static final int ABSTRACT_BIT    = 0x0400, ABSTRACT_SHIFT  = 10;
    public static final int STATIC_BIT      = 0x0800, STATIC_SHIFT    = 11;
    public static final int SYNTHETIC_BIT   = 0x1000, SYNTHETIC_SHIFT = 12;


    // ----- fields --------------------------------------------------------------------------------

    /**
     * This is the next youngest sibling that shares a conceptual parent and a name. Components have
     * siblings only when conditions kick in; consider a module that contains a class named "util"
     * in version 1 that is replaced with a package in version 2 and version 3. Some arbitrary first
     * sibling would have the identity of Class:(moduleconstant, "util") and a format of CLASS, with
     * a sibling with the identify of Package:(moduleconstant, "util") and a format of PACKAGE (and
     * possibly one further sibling if there were changes to the package structure between version
     * 2 and 3.)
     */
    private Component m_sibling;

    /**
     * This is the identity constant for the Component. Because the identity constant is of a
     * certain type (e.g. package, class, ...), it may not be shared by all of the siblings with
     * the same name, if they are of different formats.
     */
    private IdentityConstant m_constId;

    /**
     * The condition for this component that specifies under which conditions this component will
     * exist.
     */
    private ConditionalConstant m_cond;

    /**
     * This numeric value encodes all sorts of information, including access, abstract, static,
     * synthetic, and the component format.
     */
    private short m_nFlags;

    /**
     * This is a non-deserialized form of all of the children. When a Component is read from disk,
     * it can optionally lazily deserialize its children. This is possible because the "children"
     * block is length-encoded.
     */
    private byte[] m_abChildren;

    /**
     * This holds all of the children of all of the siblings, except for methods (because they are
     * identified by signature, not by name). Because a single child may turn out to be a child of
     * more than one sibling (based on which condition applies), the child can only determine its
     * real parent by asking the assumed parent's assumed parent for the child by the name of the
     * assumed parent. Similarly, the child obtained by name from this map is just the first of the
     * siblings by that name, only one of which (at most) is the child that is existent for a
     * specified name.
     */
    private Map<String, Component> m_childByName;

    /**
     * This holds all of the method children. See the explanation of {@link #m_childByName}.
     */
    private Map<MethodConstant, MethodStructure> m_methodByConstant;

    /**
     * The documentation.
     */
    private String m_sDoc;

    /**
     * For XVM structures that can be modified, this flag tracks whether or not
     * a modification has occurred.
     */
    private boolean m_fModified;
    }
