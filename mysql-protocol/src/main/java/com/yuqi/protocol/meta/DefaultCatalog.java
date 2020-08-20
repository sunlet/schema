/*
 * This file is generated by jOOQ.
 */
package com.yuqi.protocol.meta;


import org.jooq.Schema;
import org.jooq.impl.CatalogImpl;

import java.util.Arrays;
import java.util.List;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class DefaultCatalog extends CatalogImpl {

    /**
     * The reference instance of <code>DEFAULT_CATALOG</code>
     */
    public static final DefaultCatalog DEFAULT_CATALOG = new DefaultCatalog();
    private static final long serialVersionUID = 574299685;
    /**
     * The schema <code>sloth</code>.
     */
    public final Sloth SLOTH = Sloth.SLOTH;

    /**
     * No further instances allowed
     */
    private DefaultCatalog() {
        super("");
    }

    @Override
    public final List<Schema> getSchemas() {
        return Arrays.<Schema>asList(
            Sloth.SLOTH);
    }
}
