package org.openscada.opc.lib.da;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.jinterop.dcom.common.JIException;
import org.openscada.opc.dcom.common.KeyedResult;
import org.openscada.opc.dcom.common.KeyedResultSet;
import org.openscada.opc.dcom.da.OPCITEMDEF;
import org.openscada.opc.dcom.da.OPCITEMRESULT;
import org.openscada.opc.dcom.da.OPCITEMSOURCE;
import org.openscada.opc.dcom.da.OPCITEMSTATE;
import org.openscada.opc.dcom.da.impl.OPCGroupStateMgt;
import org.openscada.opc.dcom.da.impl.OPCItemMgt;
import org.openscada.opc.dcom.da.impl.OPCSyncIO;

public class Group
{
    private static Logger _log = Logger.getLogger ( Group.class );

    private Server _server = null;

    private OPCGroupStateMgt _group = null;

    private OPCItemMgt _items = null;
    
    private OPCSyncIO _syncIO = null;

    private Map<String, Integer> _itemHandleMap = new HashMap<String, Integer> ();

    private Map<Integer, Item> _itemMap = new HashMap<Integer, Item> ();

    public Group ( Server server, OPCGroupStateMgt group ) throws IllegalArgumentException, UnknownHostException, JIException
    {
        _log.debug ( "Creating new group instance with COM group " + group );
        _server = server;
        _group = group;
        _items = group.getItemManagement ();
        _syncIO = group.getSyncIO ();
    }

    public void setActive ( boolean state ) throws JIException
    {
        _group.setState ( null, state, null, null, null, null );
    }

    public boolean isActive () throws JIException
    {
        return _group.getState ().isActive ();
    }

    public String getName () throws JIException
    {
        return _group.getState ().getName ();
    }

    public void setName ( String name ) throws JIException
    {
        _group.setName ( name );
    }

    public Item addItem ( String item ) throws JIException, AddFailedException
    {
        Map<String,Item> items = addItems ( item );
        return items.get ( item );
    }
    
    public synchronized Map<String, Item> addItems ( String... items ) throws JIException, AddFailedException
    {
        // Find which items we already have
        Map<String, Integer> handles = findItems ( items );

        List<Integer> foundItems = new ArrayList<Integer> ( items.length );
        List<String> missingItems = new ArrayList<String> ();

        // separate missing items from the found ones
        for ( Map.Entry<String, Integer> entry : handles.entrySet () )
        {
            if ( entry.getValue () == null )
                missingItems.add ( entry.getKey () );
            else
                foundItems.add ( entry.getValue () );
        }

        // now fetch missing items from OPC server
        OPCITEMDEF[] itemDef = new OPCITEMDEF[missingItems.size ()];
        for ( int i = 0; i < missingItems.size (); i++ )
        {
            OPCITEMDEF def = new OPCITEMDEF ();
            def.setItemID ( missingItems.get ( i ) );
            def.setActive ( true );
            itemDef[i] = def;
        }

        // check the result and add new items
        Map<String,Integer> failedItems = new HashMap<String, Integer> (); 
        KeyedResultSet<OPCITEMDEF, OPCITEMRESULT> result = _items.add ( itemDef );
        for ( KeyedResult<OPCITEMDEF, OPCITEMRESULT> entry : result )
        {
            if ( entry.getErrorCode () == 0 )
            {
                Item item = new Item ( this, entry.getValue ().getServerHandle (), entry.getKey ().getItemID () );
                addItem ( item );
                foundItems.add ( item.getServerHandle () );
            }
            else
            {
                failedItems.put ( entry.getKey ().getItemID (), entry.getErrorCode () );
            }
        }
        
        // if we have failed items then throw an exception with the result
        if ( failedItems.size () != 0 )
            throw new AddFailedException ( failedItems, findItems ( foundItems ) );
        
        // simply return the result in case of success
        return findItems ( foundItems );
    }
    
    private synchronized void addItem ( Item item )
    {
        _itemHandleMap.put ( item.getId (), item.getServerHandle () );
        _itemMap.put ( item.getServerHandle (), item );
    }
    
    private synchronized void removeItem ( Item item )
    {
        _itemHandleMap.remove ( item.getId () );
        _itemMap.remove ( item.getServerHandle () );
    }

    private synchronized Map<String, Integer> findItems ( String[] items )
    {
        Map<String, Integer> data = new HashMap<String, Integer> ();

        for ( int i = 0; i < items.length; i++ )
        {
            data.put ( items[i], _itemHandleMap.get ( items[i] ) );
        }

        return data;
    }

    private synchronized Map<String, Item> findItems ( Collection<Integer> handles )
    {
        Map<String, Item> itemMap = new HashMap<String, Item> ();
        for ( Integer i : handles )
        {
            Item item = _itemMap.get ( i );
            if ( item != null )
            {
                itemMap.put ( item.getId (), item );
            }
        }
        return itemMap;
    }
    
    protected void checkItems ( Item [] items )
    {
        for ( Item item : items )
        {
            if ( item.getGroup () != this )
                throw new IllegalArgumentException ( "Item does not belong to this group" );
        }
    }
    
    public void setActive ( boolean state, Item... items ) throws JIException
    {
        checkItems ( items );
        
        Integer [] handles = new Integer [ items.length ];
        for ( int i = 0; i < items.length; i++ )
        {
            handles[i] = items[i].getServerHandle ();
        }
        
        _items.setActiveState ( state, handles );
    }
    
    public synchronized Map<Item, ItemState> read ( boolean device, Item... items ) throws JIException
    {
        checkItems ( items );
        
        Integer [] handles = new Integer[items.length];
        
        for ( int i = 0; i< items.length; i++ )
        {
            handles[i] = items[i].getServerHandle ();
        }
        
        KeyedResultSet<Integer, OPCITEMSTATE> states = _syncIO.read ( device ? OPCITEMSOURCE.OPC_DS_DEVICE : OPCITEMSOURCE.OPC_DS_CACHE, handles );
        
        Map<Item,ItemState> data = new HashMap<Item, ItemState> ();
        for ( KeyedResult<Integer,OPCITEMSTATE> entry : states )
        {
            Item item = _itemMap.get ( entry.getKey () );
            ItemState state = new ItemState (
                                             entry.getValue ().getValue (),
                                             entry.getValue ().getTimestamp ().asCalendar (),
                                             entry.getValue ().getQuality ()
                                             );
            data.put ( item, state );
        }
        return data;
    }
}
