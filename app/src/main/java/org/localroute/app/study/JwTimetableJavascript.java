package org.localroute.app.study;

/**
 * 在教务 WebView 内执行的课表抓取脚本。
 *
 * <p>用户已经登录正方教务（jwglxt）后，脚本在同源页面里读取当前学期的
 * <code>kbList</code> 并交给原生侧解析。脚本不读取账号、密码或页面表单，
 * 只调用学校页面自身的课表接口。
 *
 * <p>除课程外还要拿回「教务自己认为是第几周」：课表页面把当前周写在 <code>#xq</code>
 * 下拉框或 <code>xnWeeks</code> 变量里，把各周起始日期写在 <code>dates</code> 数组里。
 * 这些只决定导入后定位到哪一周，读不到就交空值——日历分支一律尽力而为，
 * 不能因为它读不到而让整次课表导入失败。
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
                var marker = path.indexOf('/jwglxt');
                if (marker < 0) {
                  fail('NOT_ON_JWGLXT', '当前页面不是教务系统');
                  return;
                }
                var prefix = path.substring(0, marker + '/jwglxt'.length);
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

                function nonEmptyOptions(select) {
                  if (!select) return [];
                  return Array.prototype.filter.call(select.options || [], function (option) {
                    return option && String(option.value).trim() !== '';
                  });
                }

                function selectedOption(doc, id) {
                  var select = doc.getElementById(id);
                  if (!select) return '';
                  var options = nonEmptyOptions(select);
                  if (!options.length) return '';
                  var chosen = options.filter(function (option) {
                    return option.hasAttribute('selected') || option.selected;
                  });
                  return String((chosen.length ? chosen[0] : options[0]).value).trim();
                }

                function isLoginPage(text) {
                  return text.indexOf('login_slogin') >= 0 &&
                      text.indexOf('csrftoken') >= 0;
                }

                // 课表首页一份 HTML 里既有学期下拉，也有周次与校历日期，只取一次。
                var indexHtml = null;
                function loadIndex(term) {
                  if (indexHtml !== null) return Promise.resolve(indexHtml);
                  var query = '?gnmkdm=' + encodeURIComponent(gnmkdm) + '&layout=default';
                  if (term && term.xnm) {
                    query += '&xnm=' + encodeURIComponent(term.xnm) +
                        '&xqm=' + encodeURIComponent(term.xqm || '');
                  }
                  return fetch(prefix + '/kbcx/xskbcx_cxXskbcxIndex.html' + query, {
                    credentials: 'include',
                    headers: {'X-Requested-With': 'XMLHttpRequest'}
                  }).then(function (response) {
                    return response.text();
                  }).then(function (text) {
                    indexHtml = text;
                    return text;
                  });
                }

                function readIndexTerm() {
                  return loadIndex(null).then(function (html) {
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

                function pad2(value) {
                  return (value < 10 ? '0' : '') + value;
                }

                function isoText(year, month, day) {
                  if (!(year >= 2000 && year <= 2100) || !(month >= 1 && month <= 12) ||
                      !(day >= 1 && day <= 31)) {
                    return '';
                  }
                  return year + '-' + pad2(month) + '-' + pad2(day);
                }

                /** 接受 Date 对象、2025-09-01、2025/9/1、20250901；读不出返回空串。 */
                function dateText(value) {
                  if (!value) return '';
                  if (Object.prototype.toString.call(value) === '[object Date]') {
                    if (isNaN(value.getTime())) return '';
                    return isoText(value.getFullYear(), value.getMonth() + 1, value.getDate());
                  }
                  var matched =
                      /^([0-9]{4})[-/.]?([0-9]{1,2})[-/.]?([0-9]{1,2})/.exec(String(value).trim());
                  if (!matched) return '';
                  return isoText(Number(matched[1]), Number(matched[2]), Number(matched[3]));
                }

                function pushDate(list, text) {
                  if (text && list.indexOf(text) < 0) list.push(text);
                }

                function weekDigits(text) {
                  var matched = /[0-9]{1,2}/.exec(String(text || ''));
                  return matched ? matched[0] : '';
                }

                /** 用户正停在课表页时，周次与校历日期就在当前文档和全局变量里。 */
                function pageCalendar() {
                  var out = {weeks: '', dates: [], count: 0};
                  try {
                    var options = nonEmptyOptions(document.getElementById('xq'));
                    if (options.length) {
                      out.count = options.length;
                      var chosen = options.filter(function (option) {
                        return option.selected || option.hasAttribute('selected');
                      });
                      out.weeks = weekDigits((chosen.length ? chosen[0] : options[0]).value);
                    }
                  } catch (ignored) {
                  }
                  try {
                    if (!out.weeks) out.weeks = weekDigits(window.xnWeeks);
                    if (window.dates && window.dates.length) {
                      Array.prototype.forEach.call(window.dates, function (value) {
                        pushDate(out.dates, dateText(value));
                      });
                    }
                  } catch (ignored) {
                  }
                  return out;
                }

                /**
                 * 课表首页脚本里的校历：<code>dates[0] = '2025-09-01'</code> 与
                 * <code>dates[0] = new Date(2025, 8, 1)</code>（月份 0 基，要加回 1）。
                 */
                function scanDates(html) {
                  var out = [];
                  var indexed =
                      /dates\\s*\\[\\s*[0-9]{1,2}\\s*\\]\\s*=\\s*(?:new\\s+Date\\s*\\(\\s*([0-9]{4})\\s*,\\s*([0-9]{1,2})\\s*,\\s*([0-9]{1,2})|['"]([^'"]{6,20})['"])/g;
                  var item;
                  while ((item = indexed.exec(html)) !== null) {
                    if (item[1]) {
                      pushDate(out, isoText(Number(item[1]), Number(item[2]) + 1, Number(item[3])));
                    } else {
                      pushDate(out, dateText(item[4]));
                    }
                  }
                  if (!out.length) {
                    var literal = /dates\\s*=\\s*new\\s+Array\\s*\\(([^)]{0,4000})\\)/.exec(html);
                    if (literal) {
                      var quoted = /['"]([^'"]{6,20})['"]/g;
                      var text;
                      while ((text = quoted.exec(literal[1])) !== null) {
                        pushDate(out, dateText(text[1]));
                      }
                    }
                  }
                  return out;
                }

                function readCalendar(term) {
                  var out = pageCalendar();
                  return loadIndex(term).then(function (html) {
                    if (!html || isLoginPage(html)) return out;
                    if (!out.weeks) {
                      var declared = /xnWeeks\\s*=\\s*['"]?([0-9]{1,2})/.exec(html);
                      if (declared) out.weeks = declared[1];
                    }
                    if (!out.dates.length) out.dates = scanDates(html);
                    var doc;
                    try {
                      doc = new DOMParser().parseFromString(html, 'text/html');
                    } catch (ignored) {
                      return out;
                    }
                    if (!doc) return out;
                    if (!out.count) {
                      out.count = nonEmptyOptions(doc.getElementById('xq')).length;
                    }
                    if (!out.weeks) out.weeks = weekDigits(selectedOption(doc, 'xq'));
                    return out;
                  }).catch(function () {
                    return out;
                  });
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

                function loadKbList(term) {
                  return tryEndpoint(term, 'xskbcx_cxXsgrkb.html').then(function (kbList) {
                    if (kbList) return kbList;
                    return tryEndpoint(term, 'xskbcx_cxXsKb.html').then(function (fallback) {
                      if (fallback) return fallback;
                      throw {kind: 'FORMAT_ERROR'};
                    });
                  });
                }

                pickTerm().then(function (term) {
                  return Promise.all([loadKbList(term), readCalendar(term)]).then(function (both) {
                    var calendar = both[1] || {};
                    return {
                      term: term,
                      kbList: both[0],
                      weeks: calendar.weeks || '',
                      count: calendar.count || 0,
                      dates: calendar.dates || []
                    };
                  });
                }).then(function (result) {
                  done({
                    ok: true,
                    xnm: result.term.xnm,
                    xqm: result.term.xqm,
                    kbList: result.kbList,
                    xnWeeks: result.weeks,
                    weekCount: result.count,
                    dates: result.dates,
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
