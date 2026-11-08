package tv.slink.app;

interface SessionStore {
    TwitchApi.Session load() throws Exception;
    void save(TwitchApi.Session session) throws Exception;
    void clear() throws Exception;
}
