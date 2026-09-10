package vn.ptit.procon.protocol;

/** Reassembles fragmented WebSocket text messages before JSON parsing. */
public final class TextFrameAssembler {
    private final StringBuilder buffer = new StringBuilder();

    public synchronized String accept(CharSequence fragment, boolean last) {
        buffer.append(fragment);
        if (!last) return null;
        String message = buffer.toString();
        buffer.setLength(0);
        return message;
    }
}
