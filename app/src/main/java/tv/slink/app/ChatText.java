package tv.slink.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatText {
    sealed interface Run permits Text, Images {}
    record Text(String text) implements Run {}
    record Images(List<ChatEmote> layers) implements Run {}
    private static final Pattern TOKENS = Pattern.compile("\\s+|\\S+");

    static List<Run> runs(ChatMessage message, Map<String, ChatEmote> emotes) {
        List<Run> result = new ArrayList<>();
        int images = 0;
        for (ChatMessage.Fragment fragment : message.fragments()) {
            if (!fragment.emoteId().isEmpty() && images < 12) {
                addImage(result, ChatEmote.twitch(fragment)); images++; continue;
            }
            Matcher tokens = TOKENS.matcher(fragment.text());
            while (tokens.find()) {
                String text = tokens.group(); ChatEmote emote = emotes.get(text);
                if (emote != null && images < 12) { addImage(result, emote); images++; }
                else result.add(new Text(text));
            }
        }
        return result;
    }

    private static void addImage(List<Run> runs, ChatEmote emote) {
        int index = runs.size() - 1;
        if (index >= 0 && runs.get(index) instanceof Text text && text.text().isBlank()) index--;
        if (emote.zeroWidth() && index >= 0 && runs.get(index) instanceof Images images && images.layers().size() < 4) {
            List<ChatEmote> layers = new ArrayList<>(images.layers()); layers.add(emote);
            while (runs.size() > index) runs.remove(runs.size() - 1);
            runs.add(new Images(List.copyOf(layers)));
        } else runs.add(new Images(List.of(emote)));
    }
    private ChatText() {}
}
