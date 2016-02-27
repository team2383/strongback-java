package org.strongback;

import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;

public class Executables implements Executable {
    protected final CopyOnWriteArrayList<Executable> executables = new CopyOnWriteArrayList<>();

    public boolean register(Executable e) {
        return executables.addIfAbsent(e);
    }

    public boolean unregister(Executable e) {
        return executables.remove(e);
    }

    public void unregisterAll() {
        executables.clear();
    }

    public Iterator<Executable> iterator() {
        return executables.iterator();
    }

    @Override
    public void execute(long timeInMillis) {
        executables.forEach((e) -> e.execute(timeInMillis));
    }
}
