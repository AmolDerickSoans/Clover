/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.floens.chan.core.site.sites.chan4;

import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;

import org.floens.chan.chan.ChanLoaderRequest;
import org.floens.chan.chan.ChanLoaderRequestParams;
import org.floens.chan.core.model.Board;
import org.floens.chan.core.model.Loadable;
import org.floens.chan.core.model.Post;
import org.floens.chan.core.settings.StringSetting;
import org.floens.chan.core.site.Boards;
import org.floens.chan.core.site.Site;
import org.floens.chan.core.site.SiteAuthentication;
import org.floens.chan.core.site.SiteEndpoints;
import org.floens.chan.core.site.SiteRequestModifier;
import org.floens.chan.core.site.http.DeleteRequest;
import org.floens.chan.core.site.http.HttpCall;
import org.floens.chan.core.site.http.HttpCallManager;
import org.floens.chan.core.site.http.LoginRequest;
import org.floens.chan.core.site.http.LoginResponse;
import org.floens.chan.core.site.http.Reply;
import org.floens.chan.utils.AndroidUtils;
import org.floens.chan.utils.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.inject.Inject;

import okhttp3.HttpUrl;
import okhttp3.Request;

import static org.floens.chan.Chan.getGraph;

public class Chan4 implements Site {
    private static final String TAG = "Chan4";

    private static final Random random = new Random();

    @Inject
    HttpCallManager httpCallManager;

    @Inject
    RequestQueue requestQueue;

    private final SiteEndpoints endpoints = new SiteEndpoints() {
        private final HttpUrl a = new HttpUrl.Builder()
                .scheme("https")
                .host("a.4cdn.org")
                .build();

        private final HttpUrl i = new HttpUrl.Builder()
                .scheme("https")
                .host("i.4cdn.org")
                .build();

        private final HttpUrl t = new HttpUrl.Builder()
                .scheme("https")
                .host("t.4cdn.org")
                .build();

        private final HttpUrl s = new HttpUrl.Builder()
                .scheme("https")
                .host("s.4cdn.org")
                .build();

        private final HttpUrl sys = new HttpUrl.Builder()
                .scheme("https")
                .host("sys.4chan.org")
                .build();

        @Override
        public HttpUrl catalog(Board board) {
            return a.newBuilder()
                    .addPathSegment(board.code)
                    .addPathSegment("catalog.json")
                    .build();
        }

        @Override
        public HttpUrl thread(Board board, Loadable loadable) {
            return a.newBuilder()
                    .addPathSegment(board.code)
                    .addPathSegment("thread")
                    .addPathSegment(loadable.no + ".json")
                    .build();
        }

        @Override
        public HttpUrl imageUrl(Post.Builder post, Map<String, String> arg) {
            return i.newBuilder()
                    .addPathSegment(post.board.code)
                    .addPathSegment(arg.get("tim") + "." + arg.get("ext"))
                    .build();
        }

        @Override
        public HttpUrl thumbnailUrl(Post.Builder post, boolean spoiler, Map<String, String> arg) {
            if (spoiler) {
                HttpUrl.Builder image = s.newBuilder()
                        .addPathSegment("image");
                if (post.board.customSpoilers >= 0) {
                    int i = random.nextInt(post.board.customSpoilers) + 1;
                    image.addPathSegment("spoiler-" + post.board.code + i + ".png");
                } else {
                    image.addPathSegment("spoiler.png");
                }
                return image.build();
            } else {
                return t.newBuilder()
                        .addPathSegment(post.board.code)
                        .addPathSegment(arg.get("tim") + "s.jpg")
                        .build();
            }
        }

        @Override
        public HttpUrl icon(Post.Builder post, String icon, Map<String, String> arg) {
            HttpUrl.Builder b = s.newBuilder().addPathSegment("image");

            switch (icon) {
                case "country":
                    b.addPathSegment("country");
                    b.addPathSegment(arg.get("country_code").toLowerCase(Locale.ENGLISH) + ".gif");
                    break;
                case "since4pass":
                    b.addPathSegment("minileaf.gif");
                    break;
            }

            return b.build();
        }

        @Override
        public HttpUrl boards() {
            return a.newBuilder()
                    .addPathSegment("boards.json")
                    .build();
        }

        @Override
        public HttpUrl reply(Loadable loadable) {
            return sys.newBuilder()
                    .addPathSegment(loadable.board.code)
                    .addPathSegment("post")
                    .build();
        }

        @Override
        public HttpUrl delete(Post post) {
            return sys.newBuilder()
                    .addPathSegment(post.board.code)
                    .addPathSegment("imgboard.php")
                    .build();
        }

        @Override
        public HttpUrl report(Post post) {
            return sys.newBuilder()
                    .addPathSegment(post.board.code)
                    .addPathSegment("imgboard.php")
                    .addQueryParameter("mode", "report")
                    .addQueryParameter("no", String.valueOf(post.no))
                    .build();
        }

        @Override
        public HttpUrl login() {
            return sys.newBuilder()
                    .addPathSegment("auth")
                    .build();
        }
    };

    private SiteRequestModifier siteRequestModifier = new SiteRequestModifier() {
        @Override
        public void modifyHttpCall(HttpCall httpCall, Request.Builder requestBuilder) {
            if (isLoggedIn()) {
                requestBuilder.addHeader("Cookie", "pass_id=" + passToken.get());
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void modifyWebView(WebView webView) {
            final HttpUrl sys = new HttpUrl.Builder()
                    .scheme("https")
                    .host("sys.4chan.org")
                    .build();

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookie();
            if (isLoggedIn()) {
                String[] passCookies = {
                        "pass_enabled=1;",
                        "pass_id=" + passToken.get() + ";"
                };
                String domain = sys.scheme() + "://" + sys.host() + "/";
                for (String cookie : passCookies) {
                    cookieManager.setCookie(domain, cookie);
                }
            }
        }
    };

    private SiteAuthentication authentication = new SiteAuthentication() {
        @Override
        public boolean requireAuthentication(AuthenticationRequestType type) {
            if (type == AuthenticationRequestType.POSTING) {
                return !isLoggedIn();
            }

            return false;
        }
    };

    // Legacy settings that were global before
    private final StringSetting passUser;
    private final StringSetting passPass;
    private final StringSetting passToken;

    public Chan4() {
        getGraph().inject(this);

        SharedPreferences p = AndroidUtils.getPreferences();
        passUser = new StringSetting(p, "preference_pass_token", "");
        passPass = new StringSetting(p, "preference_pass_pin", "");
        // token was renamed, before it meant the username, now it means the token returned
        // from the server that the cookie is set to.
        passToken = new StringSetting(p, "preference_pass_id", "");
    }

    /**
     * <b>Note: very special case, only this site may have 0 as the return value.<br>
     * This is for backwards compatibility when we didn't support multi-site yet.</b>
     *
     * @return {@inheritDoc}
     */
    @Override
    public int id() {
        return 0;
    }

    @Override
    public boolean feature(Feature feature) {
        switch (feature) {
            case POSTING:
                // yes, we support posting.
                return true;
            case LOGIN:
                // 4chan pass.
                return true;
            case POST_DELETE:
                // yes, with the password saved when posting.
                return true;
            case POST_REPORT:
                // yes, with a custom url
                return true;
            default:
                return false;
        }
    }

    @Override
    public BoardsType boardsType() {
        // yes, boards.json
        return BoardsType.DYNAMIC;
    }

    @Override
    public String desktopUrl(Loadable loadable, @Nullable Post post) {
        if (loadable.isCatalogMode()) {
            return "https://boards.4chan.org/" + loadable.board.code + "/";
        } else if (loadable.isThreadMode()) {
            String url = "https://boards.4chan.org/" + loadable.board.code + "/thread/" + loadable.no;
            if (post != null) {
                url += "#p" + post.no;
            }
            return url;
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public boolean boardFeature(BoardFeature boardFeature, Board board) {
        switch (boardFeature) {
            case POSTING_IMAGE:
                // yes, we support image posting.
                return true;
            case POSTING_SPOILER:
                // depends if the board supports it.
                return board.spoilers;
            default:
                return false;
        }
    }

    @Override
    public Board board(String name) {
        List<Board> allBoards = getGraph().getBoardManager().getAllBoards();
        for (Board board : allBoards) {
            if (board.code.equals(name)) {
                return board;
            }
        }

        return null;
    }

    @Override
    public SiteEndpoints endpoints() {
        return endpoints;
    }

    @Override
    public SiteRequestModifier requestModifier() {
        return siteRequestModifier;
    }

    @Override
    public SiteAuthentication authentication() {
        return authentication;
    }

    @Override
    public void boards(final BoardsListener listener) {
        requestQueue.add(new Chan4BoardsRequest(this, new Response.Listener<List<Board>>() {
            @Override
            public void onResponse(List<Board> response) {
                listener.onBoardsReceived(new Boards(response));
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Logger.e(TAG, "Failed to get boards from server", error);

                // API fail, provide some default boards
                List<Board> list = new ArrayList<>();
                list.add(new Board(Chan4.this, "Technology", "g", true, true));
                list.add(new Board(Chan4.this, "Food & Cooking", "ck", true, true));
                list.add(new Board(Chan4.this, "Do It Yourself", "diy", true, true));
                list.add(new Board(Chan4.this, "Animals & Nature", "an", true, true));
                Collections.shuffle(list);
                listener.onBoardsReceived(new Boards(list));
            }
        }));
    }

    @Override
    public ChanLoaderRequest loaderRequest(ChanLoaderRequestParams request) {
        return new ChanLoaderRequest(new Chan4ReaderRequest(request));
    }

    @Override
    public void post(Reply reply, final PostListener postListener) {
        httpCallManager.makeHttpCall(new Chan4ReplyHttpCall(this, reply), new HttpCall.HttpCallback<Chan4ReplyHttpCall>() {
            @Override
            public void onHttpSuccess(Chan4ReplyHttpCall httpPost) {
                postListener.onPostComplete(httpPost, httpPost.replyResponse);
            }

            @Override
            public void onHttpFail(Chan4ReplyHttpCall httpPost, Exception e) {
                postListener.onPostError(httpPost);
            }
        });
    }

    @Override
    public void delete(DeleteRequest deleteRequest, final DeleteListener deleteListener) {
        httpCallManager.makeHttpCall(new Chan4DeleteHttpCall(this, deleteRequest), new HttpCall.HttpCallback<Chan4DeleteHttpCall>() {
            @Override
            public void onHttpSuccess(Chan4DeleteHttpCall httpPost) {
                deleteListener.onDeleteComplete(httpPost, httpPost.deleteResponse);
            }

            @Override
            public void onHttpFail(Chan4DeleteHttpCall httpPost, Exception e) {
                deleteListener.onDeleteError(httpPost);
            }
        });
    }

    @Override
    public void login(LoginRequest loginRequest, final LoginListener loginListener) {
        passUser.set(loginRequest.user);
        passPass.set(loginRequest.pass);

        httpCallManager.makeHttpCall(new Chan4PassHttpCall(this, loginRequest), new HttpCall.HttpCallback<Chan4PassHttpCall>() {
            @Override
            public void onHttpSuccess(Chan4PassHttpCall httpCall) {
                LoginResponse loginResponse = httpCall.loginResponse;
                if (loginResponse.success) {
                    passToken.set(loginResponse.token);
                }
                loginListener.onLoginComplete(httpCall, loginResponse);
            }

            @Override
            public void onHttpFail(Chan4PassHttpCall httpCall, Exception e) {
                loginListener.onLoginError(httpCall);
            }
        });
    }

    @Override
    public void logout() {
        passToken.set("");
    }

    @Override
    public boolean isLoggedIn() {
        return !passToken.get().isEmpty();
    }

    @Override
    public LoginRequest getLoginDetails() {
        return new LoginRequest(passUser.get(), passPass.get());
    }
}
