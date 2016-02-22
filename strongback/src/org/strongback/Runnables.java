package org.strongback;

import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

public class Runnables implements Runnable {
    protected final CopyOnWriteArrayList<Runnable> runnables = new CopyOnWriteArrayList<>();

    public boolean register(Runnable e) {
        return runnables.addIfAbsent(e);
    }

    public boolean unregister(Runnable e) {
        return runnables.remove(e);
    }

    public void unregisterAll() {
        runnables.clear();
    }

    public Iterator<Runnable> iterator() {
        return runnables.iterator();
    }

    @Override
    public void run() {
        runnables.forEach((r) -> r.run());
    }
}
