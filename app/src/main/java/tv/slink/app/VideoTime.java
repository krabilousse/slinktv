package tv.slink.app;

final class VideoTime {
    static long parse(String input, long duration) {
        String value = input.trim();
        if (!value.matches("[0-9]{1,4}:[0-5][0-9](:[0-5][0-9])?"))
            throw new IllegalArgumentException("Enter MM:SS or HH:MM:SS.");
        String[] pieces = value.split(":");
        long seconds = 0;
        for (String piece : pieces) seconds = seconds * 60 + Long.parseLong(piece);
        long position = seconds * 1000;
        if (duration <= 0 || position > duration) throw new IllegalArgumentException("Time must be within this video.");
        return position;
    }
    private VideoTime() {}
}
