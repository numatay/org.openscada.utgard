package org.openscada.opc.lib.da;

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.jinterop.dcom.common.JIException;
import org.openscada.opc.lib.common.NotConnectedException;

public class SyncAccess implements Runnable
{
    private static Logger _log = Logger.getLogger ( SyncAccess.class );

    private Server _server = null;

    private Group _group = null;

    private Map<Item, DataCallback> _items = new HashMap<Item, DataCallback> ();

    private Map<String, Item> _itemMap = new HashMap<String, Item> ();

    private boolean _active = false;

    private Thread _runner = null;

    private int _delay = 0;

    public SyncAccess ( Server server, int delay ) throws IllegalArgumentException, UnknownHostException, NotConnectedException, JIException, DuplicateGroupException
    {
        _server = server;
        _group = _server.addGroup ();
        _group.setActive ( false );
        _delay = delay;
    }

    public synchronized void start () throws JIException
    {
        if ( _active )
            return;

        _group.setActive ( true );
        _active = true;

        _runner = new Thread ( this );
        _runner.setDaemon ( true );
        _runner.start ();
    }

    public synchronized void stop () throws JIException
    {
        if ( !_active )
            return;

        _active = false;
        _group.setActive ( false );

        _runner = null;
    }

    public synchronized void addItem ( String itemId, DataCallback dataCallback ) throws JIException, AddFailedException
    {
        if ( _items.containsKey ( itemId ) )
            return;

        Item item = _group.addItem ( itemId );
        _items.put ( item, dataCallback );
        _itemMap.put ( itemId, item );
    }

    public synchronized void removeItem ( String itemId )
    {
        if ( !_items.containsKey ( itemId ) )
            return;

        Item item = _itemMap.remove ( itemId );
        _items.remove ( item );
    }

    public void run ()
    {
        while ( _active )
        {
            try
            {
                runOnce ();
                Thread.sleep ( _delay );
            }
            catch ( Exception e )
            {
                _log.error ( "Sync read failed", e );
                try
                {
                    stop ();
                }
                catch ( Exception e1 )
                {
                    _log.fatal ( "Failed to recover sync read error", e1 );
                }
            }
        }
    }

    protected synchronized void runOnce () throws JIException
    {
        if ( !_active )
            return;

        Item[] items = _items.keySet ().toArray ( new Item[_items.size ()] );

        Map<Item, ItemState> result = _group.read ( false, items );
        for ( Map.Entry<Item, ItemState> entry : result.entrySet () )
        {
            _items.get ( entry.getKey () ).changed ( entry.getKey (), entry.getValue () );
        }

    }
}
