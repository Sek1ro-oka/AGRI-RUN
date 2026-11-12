package org.localroute.app.study;

/**
 * 在教务 WebView 内执行的课表抓取脚本。
 *
 * <p>用户已经登录正方教务（jwglxt）后，脚本在同源页面里读取当前学期的
 * <code>kbList</code> 并交给原生侧解析。脚本不读取账号、密码或页面表单，
 * 只调用学校页面自身的课表接口。
 */
public final class JwTimetableJavascript {

    public static final String FETCH_JS = """
            (function () {
              function done(payload) {
                try {
                  window.LegionJw.onResult(JSON.stringify(payload));
                } catch (ignored) {
                }
              }
              function fail(kind, error) {
                done({ok: false, kind: kind, error: error || ''});
              }
              try {
                var path = location.pathname || '';
                if (path.indexOf('/jwglxt/') < 0) {
                  fail('NOT_ON_JWGLXT', '当前页面不是教务系统');
                  return;
                }
                var params = {};
                location.search.substring(1).split('&').forEach(function (part) {
                  if (!part) return;
                  var index = part.indexOf('=');
                  var key = index < 0 ? part : part.substring(0, index);
                  var value = index < 0 ? '' : part.substring(index + 1);
                  try {
                    params[decodeURIComponent(key)] = decodeURIComponent(value);
                  } catch (ignored) {
                    params[key] = value;
                  }
                });
                var gnmkdm = params['gnmkdm'] || 'N2151';
                var prefix = '/jwglxt';

                function selectedOption(doc, id) {
                  var select = doc.getElementById(id);
                  if (!select) return '';
                  var options = Array.prototype.filter.call(select.options, function (option) {
                    return option && String(option.value).trim() !== '';
                  });
                  if (!options.length) return '';
                  var chosen = options.filter(function (option) {
                    return option.hasAttribute('selected') || option.selected;
                  });
                  return String((chosen.length ? chosen[0] : options[0]).value).trim();
                }

                function readIndexTerm() {
                  return fetch(prefix + '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' +
                      encodeURIComponent(gnmkdm) + '&layout=default', {
                    credentials: 'include',
                    headers: {'X-Requested-With': 'XMLHttpRequest'}
                  }).then(function (response) {
                    return response.text();
                  }).then(function (html) {
                    if (isLoginPage(html)) throw {kind: 'SESSION_EXPIRED'};
                    var doc = new DOMParser().parseFromString(html, 'text/html');
                    var xnm = selectedOption(doc, 'xnm');
                    var xqm = selectedOption(doc, 'xqm');
                    if (!xnm || !xqm) throw {kind: 'TERM_UNKNOWN'};
                    return {xnm: xnm, xqm: xqm};
                  });
                }

                function pickTerm() {
                  if (params['xnm'] && params['xqm']) {
                    return Promise.resolve({xnm: params['xnm'], xqm: params['xqm']});
                  }
                  var xnmNode = document.querySelector('#xnm');
                  var xqmNode = document.querySelector('#xqm');
                  if (xnmNode && xqmNode && xnmNode.value && xqmNode.value) {
                    return Promise.resolve({
                      xnm: String(xnmNode.value).trim(),
                      xqm: String(xqmNode.value).trim()
                    });
                  }
                  return readIndexTerm();
                }

                function isLoginPage(text) {
                  return text.indexOf('login_slogin') >= 0 &&
                      text.indexOf('csrftoken') >= 0;
                }

                function findKbList(value, depth) {
                  if (!value || typeof value !== 'object' || depth > 5) return null;
                  if (Array.isArray(value)) {
                    for (var i = 0; i < value.length; i++) {
                      var found = findKbList(value[i], depth + 1);
                      if (found) return found;
                    }
                    return null;
                  }
                  if (Object.prototype.hasOwnProperty.call(value, 'kbList') &&
                      Array.isArray(value.kbList)) {
                    return value.kbList;
                  }
                  for (var key in value) {
                    if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
                    var nested = findKbList(value[key], depth + 1);
                    if (nested) return nested;
                  }
                  return null;
                }

                function postTimetable(term, endpoint) {
                  return fetch(prefix + '/kbcx/' + endpoint + '?gnmkdm=' +
                      encodeURIComponent(gnmkdm), {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                      'X-Requested-With': 'XMLHttpRequest',
                      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                    },
                    body: 'xnm=' + encodeURIComponent(term.xnm) +
                        '&xqm=' + encodeURIComponent(term.xqm) +
                        '&kzlx=ck&xsdm=&kclbdm=&kclxdm='
                  }).then(function (response) {
                    return response.text();
                  });
                }

                function tryEndpoint(term, endpoint) {
                  return postTimetable(term, endpoint).then(function (text) {
                    if (isLoginPage(text)) throw {kind: 'SESSION_EXPIRED'};
                    var data;
                    try {
                      data = JSON.parse(text);
                    } catch (ignored) {
                      return null;
                    }
                    var kbList = findKbList(data, 0);
                    return kbList ? kbList : null;
                  });
                }

                pickTerm().then(function (term) {
                  return tryEndpoint(term, 'xskbcx_cxXsgrkb.html').then(function (kbList) {
                    if (kbList) return {term: term, kbList: kbList};
                    return tryEndpoint(term, 'xskbcx_cxXsKb.html').then(function (fallback) {
                      if (fallback) return {term: term, kbList: fallback};
                      throw {kind: 'FORMAT_ERROR'};
                    });
                  });
                }).then(function (result) {
                  done({
                    ok: true,
                    xnm: result.term.xnm,
                    xqm: result.term.xqm,
                    kbList: result.kbList,
                    empty: result.kbList.length === 0
                  });
                }).catch(function (error) {
                  if (error && error.kind) {
                    fail(error.kind, error.error || '');
                  } else {
                    fail('NETWORK_ERROR', String(error && error.message ? error.message : error));
                  }
                });
              } catch (error) {
                fail('FORMAT_ERROR', String(error));
              }
            })();
            """;

    private JwTimetableJavascript() {
    }
}
