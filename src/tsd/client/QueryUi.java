// This file is part of OpenTSDB.
// Copyright (C) 2010  StumbleUpon, Inc.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package tsd.client;

/*
 * DISCLAIMER
 * This my first GWT code ever, so it's most likely horribly wrong as I've had
 * virtually no exposure to the technology except through the tutorial. --tsuna
 */

import java.util.ArrayList;
import java.util.Date;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.dom.client.ErrorEvent;
import com.google.gwt.event.dom.client.ErrorHandler;
import com.google.gwt.event.logical.shared.BeforeSelectionEvent;
import com.google.gwt.event.logical.shared.BeforeSelectionHandler;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.DecoratedTabPanel;
import com.google.gwt.user.client.ui.DecoratorPanel;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.InlineLabel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;

/**
 * Root class for the 'query UI'.
 * Manages the entire UI, forms to query the TSDB and other misc panels.
 */
public class QueryUi implements EntryPoint {
  // Some URLs we use to fetch data from the TSD.
  private static final String AGGREGATORS_URL = "/aggregators";
  private static final String LOGS_URL = "/logs?json";
  private static final String STATS_URL = "/stats?json";
  private static final String VERSION_URL = "/version?json";

  private static final DateTimeFormat FULLDATE =
    DateTimeFormat.getFormat("yyyy/MM/dd-HH:mm:ss");

  private final Label current_error = new Label();

  private final DateTimeBox start_datebox = new DateTimeBox();
  private final DateTimeBox end_datebox = new DateTimeBox();
  private final CheckBox autoreoload = new CheckBox("Autoreload");
  private final ValidatedTextBox autoreoload_interval = new ValidatedTextBox();
  private Timer autoreoload_timer;

  private final ValidatedTextBox yrange = new ValidatedTextBox();
  private final ValidatedTextBox y2range = new ValidatedTextBox();
  private final ValidatedTextBox wxh = new ValidatedTextBox();

  /**
   * Handles every change to the query form and gets a new graph.
   * Whenever the user changes one of the parameters of the graph, we want
   * to automatically get a new graph.
   */
  private final EventsHandler refreshgraph = new EventsHandler() {
    protected <H extends EventHandler> void onEvent(final DomEvent<H> event) {
      refreshGraph();
    }
  };

  /** List of known aggregation functions.  Fetched once from the server. */
  private final ArrayList<String> aggregators = new ArrayList<String>();

  private final DecoratedTabPanel metrics = new DecoratedTabPanel();

  private final Image graph = new Image();
  private final Label graphstatus = new Label();
  /** Remember the last URI requested to avoid requesting twice the same. */
  private String lastgraphuri;
  /** How many graph requests we make.  */
  private int nrequests = 0;

  // Other misc panels.
  private final FlexTable logs = new FlexTable();
  private final FlexTable stats_table = new FlexTable();
  private final HTML build_data = new HTML("Loading...");

  /**
   * This is the entry point method.
   */
  public void onModuleLoad() {
    asyncGetJson(AGGREGATORS_URL, new GotJsonCallback() {
      public void got(final JSONValue json) {
        // Do we need more manual type checking?  Not sure what will happen
        // in the browser if something other than an array is returned.
        final JSONArray aggs = json.isArray();
        for (int i = 0; i < aggs.size(); i++) {
          aggregators.add(aggs.get(i).isString().stringValue());
        }
        ((MetricForm) metrics.getWidget(0)).setAggregators(aggregators);
      }
    });

    // All UI elements need to regenerate the graph when changed.
    {
      final ValueChangeHandler<Date> vch = new ValueChangeHandler<Date>() {
        public void onValueChange(final ValueChangeEvent<Date> event) {
          refreshGraph();
        }
      };
      TextBox tb = start_datebox.getTextBox();
      tb.addBlurHandler(refreshgraph);
      tb.addKeyPressHandler(refreshgraph);
      start_datebox.addValueChangeHandler(vch);
      tb = end_datebox.getTextBox();
      tb.addBlurHandler(refreshgraph);
      tb.addKeyPressHandler(refreshgraph);
      end_datebox.addValueChangeHandler(vch);
    }
    autoreoload_interval.addBlurHandler(refreshgraph);
    autoreoload_interval.addKeyPressHandler(refreshgraph);
    yrange.addBlurHandler(refreshgraph);
    yrange.addKeyPressHandler(refreshgraph);
    y2range.addBlurHandler(refreshgraph);
    y2range.addKeyPressHandler(refreshgraph);
    wxh.addBlurHandler(refreshgraph);
    wxh.addKeyPressHandler(refreshgraph);

    yrange.setValidationRegexp("^("                            // Nothing or
                               + "|\\[([-+.0-9eE]+|\\*)?"      // "[start
                               + ":([-+.0-9eE]+|\\*)?\\])$");  //   :end]"
    yrange.setVisibleLength(5);
    yrange.setMaxLength(44);  // MAX=2^26=20 chars: "[-$MAX:$MAX]"
    yrange.setText("[0:]");

    y2range.setValidationRegexp("^("                            // Nothing or
                                + "|\\[([-+.0-9eE]+|\\*)?"      // "[start
                                + ":([-+.0-9eE]+|\\*)?\\])$");  //   :end]"
    y2range.setVisibleLength(5);
    y2range.setMaxLength(44);  // MAX=2^26=20 chars: "[-$MAX:$MAX]"
    y2range.setText("[0:]");
    y2range.setEnabled(false);

    wxh.setValidationRegexp("^[1-9][0-9]{2,}x[1-9][0-9]{2,}$");  // 100x100
    wxh.setVisibleLength(9);
    wxh.setMaxLength(11);  // 99999x99999
    wxh.setText((Window.getClientWidth() - 20) + "x"
                + (Window.getClientHeight() * 4 / 5));

    final FlexTable table = new FlexTable();
    table.setText(0, 0, "From");
    {
      final HorizontalPanel hbox = new HorizontalPanel();
      hbox.add(new InlineLabel("To"));
      final Anchor now = new Anchor("(now)");
      now.addClickHandler(new ClickHandler() {
        public void onClick(final ClickEvent event) {
          end_datebox.setValue(new Date());
          refreshGraph();
        }
      });
      hbox.add(now);
      hbox.add(autoreoload);
      hbox.setWidth("100%");
      table.setWidget(0, 1, hbox);
    }
    autoreoload.addClickHandler(new ClickHandler() {
      public void onClick(final ClickEvent event) {
        if (autoreoload.isChecked()) {
          final HorizontalPanel hbox = new HorizontalPanel();
          hbox.setWidth("100%");
          hbox.add(new InlineLabel("Every:"));
          hbox.add(autoreoload_interval);
          hbox.add(new InlineLabel("seconds"));
          table.setWidget(1, 1, hbox);
          if (autoreoload_interval.getValue().isEmpty()) {
            autoreoload_interval.setValue("15");
          }
          autoreoload_interval.setFocus(true);
          lastgraphuri = "";  // Force refreshGraph.
          refreshGraph();     // Trigger the 1st auto-reload
        } else {
          table.setWidget(1, 1, end_datebox);
        }
      }
    });
    autoreoload_interval.setValidationRegexp("^([5-9]|[1-9][0-9]+)$");  // >=5s
    autoreoload_interval.setMaxLength(4);
    autoreoload_interval.setVisibleLength(8);

    table.setWidget(1, 0, start_datebox);
    table.setWidget(1, 1, end_datebox);
    {
      final HorizontalPanel hbox = new HorizontalPanel();
      hbox.add(new InlineLabel("Y Range:"));
      hbox.add(yrange);
      hbox.add(new InlineLabel(". Y2:"));
      hbox.add(y2range);
      table.setWidget(2, 0, hbox);
    }
    {
      final HorizontalPanel hbox = new HorizontalPanel();
      hbox.add(new InlineLabel("WxH:"));
      hbox.add(wxh);
      table.setWidget(2, 1, hbox);
    }
    {
      final MetricForm.MetricChangeHandler metric_change_handler =
        new MetricForm.MetricChangeHandler() {
          public void onMetricChange(final MetricForm metric) {
            final int index = metrics.getWidgetIndex(metric);
            metrics.getTabBar().setTabText(index, getTabTitle(metric));
          }
          private String getTabTitle(final MetricForm metric) {
            final String metrictext = metric.getMetric();
            final int last_period = metrictext.lastIndexOf('.');
            if (last_period < 0) {
              return metrictext;
            }
            return metrictext.substring(last_period + 1);
          }
        };
      final EventsHandler updatey2range = new EventsHandler() {
        protected <H extends EventHandler> void onEvent(final DomEvent<H> event) {
          for (final Widget metric : metrics) {
            if (!(metric instanceof MetricForm)) {
              continue;
            }
            if (((MetricForm) metric).x1y2().getValue()) {
              y2range.setEnabled(true);
              return;
            }
          }
          y2range.setEnabled(false);
        }
      };
      final MetricForm metric = new MetricForm(refreshgraph);
      metric.x1y2().addClickHandler(updatey2range);
      metric.setMetricChangeHandler(metric_change_handler);
      metrics.add(metric, "metric 1");
      metrics.selectTab(0);
      metrics.add(new InlineLabel("Loading..."), "+");
      metrics.addBeforeSelectionHandler(new BeforeSelectionHandler<Integer>() {
        public void onBeforeSelection(final BeforeSelectionEvent<Integer> event) {
          final int item = event.getItem();
          final int nitems = metrics.getWidgetCount();
          if (item == nitems - 1) {  // Last item: the "+" was clicked.
            event.cancel();
            final MetricForm metric = new MetricForm(refreshgraph);
            metric.x1y2().addClickHandler(updatey2range);
            metric.setMetricChangeHandler(metric_change_handler);
            metric.setAggregators(aggregators);
            metrics.insert(metric, "metric " + nitems, item);
            metrics.selectTab(item);
            metric.setFocus(true);
          }
        }
      });
      table.setWidget(3, 0, metrics);
    }
    table.getFlexCellFormatter().setColSpan(3, 0, 2);

    final DecoratorPanel decorator = new DecoratorPanel();
    decorator.setWidget(table);
    final VerticalPanel graphpanel = new VerticalPanel();
    graphpanel.add(decorator);
    {
      final VerticalPanel graphvbox = new VerticalPanel();
      graphvbox.add(graphstatus);
      graph.setVisible(false);
      graphvbox.add(graph);
      graph.addErrorHandler(new ErrorHandler() {
        public void onError(final ErrorEvent event) {
          graphstatus.setText("Oops, failed to load the graph.");
        }
      });
      graphpanel.add(graphvbox);
    }
    final DecoratedTabPanel mainpanel = new DecoratedTabPanel();
    mainpanel.setWidth("100%");
    mainpanel.add(graphpanel, "Graph");
    mainpanel.add(stats_table, "Stats");
    mainpanel.add(logs, "Logs");
    mainpanel.add(build_data, "Version");
    mainpanel.selectTab(0);
    mainpanel.addBeforeSelectionHandler(new BeforeSelectionHandler<Integer>() {
      public void onBeforeSelection(final BeforeSelectionEvent<Integer> event) {
        clearError();
        final int item = event.getItem();
        switch (item) {
          case 1: refreshStats(); return;
          case 2: refreshLogs(); return;
          case 3: refreshVersion(); return;
        }
      }
    });
    final VerticalPanel root = new VerticalPanel();
    root.setWidth("100%");
    root.add(current_error);
    current_error.setVisible(false);
    current_error.addStyleName("dateBoxFormatError");
    root.add(mainpanel);
    RootPanel.get("queryuimain").add(root);
  }

  private void refreshStats() {
    asyncGetJson(STATS_URL, new GotJsonCallback() {
      public void got(final JSONValue json) {
        final JSONArray stats = json.isArray();
        final int nstats = stats.size();
        for (int i = 0; i < nstats; i++) {
          final String stat = stats.get(i).isString().stringValue();
          String part = stat.substring(0, stat.indexOf(' '));
          stats_table.setText(i, 0, part);  // metric
          int pos = part.length() + 1;
          part = stat.substring(pos, stat.indexOf(' ', pos));
          stats_table.setText(i, 1, part);  // timestamp
          pos += part.length() + 1;
          part = stat.substring(pos, stat.indexOf(' ', pos));
          stats_table.setText(i, 2, part);  // value
          pos += part.length() + 1;
          stats_table.setText(i, 3, stat.substring(pos));  // tags
        }
      }
    });
  }

  private void refreshVersion() {
    asyncGetJson(VERSION_URL, new GotJsonCallback() {
      public void got(final JSONValue json) {
        final JSONObject bd = json.isObject();
        final JSONString shortrev = bd.get("short_revision").isString();
        final JSONString status = bd.get("repo_status").isString();
        final JSONNumber stamp = bd.get("timestamp").isNumber();
        final JSONString user = bd.get("user").isString();
        final JSONString host = bd.get("host").isString();
        final JSONString repo = bd.get("repo").isString();
        build_data.setHTML(
          "OpenTSDB built from revision " + shortrev.stringValue()
          + " in a " + status.stringValue() + " state<br/>"
          + "Built on " + new Date((long) (stamp.doubleValue() * 1000))
          + " by " + user.stringValue() + '@' + host.stringValue()
          + ':' + repo.stringValue());
      }
    });
  }

  private void refreshLogs() {
    asyncGetJson(LOGS_URL, new GotJsonCallback() {
      public void got(final JSONValue json) {
        final JSONArray logmsgs = json.isArray();
        final int nmsgs = logmsgs.size();
        final FlexTable.FlexCellFormatter fcf = logs.getFlexCellFormatter();
        final FlexTable.RowFormatter rf = logs.getRowFormatter();
        for (int i = 0; i < nmsgs; i++) {
          final String msg = logmsgs.get(i).isString().stringValue();
          String part = msg.substring(0, msg.indexOf('\t'));
          logs.setText(i * 2, 0,
                       new Date(Integer.valueOf(part) * 1000L).toString());
          logs.setText(i * 2 + 1, 0, "");  // So we can change the style ahead.
          int pos = part.length() + 1;
          part = msg.substring(pos, msg.indexOf('\t', pos));
          if ("WARN".equals(part)) {
            rf.getElement(i * 2).getStyle().setBackgroundColor("#FCC");
            rf.getElement(i * 2 + 1).getStyle().setBackgroundColor("#FCC");
          } else if ("ERROR".equals(part)) {
            rf.getElement(i * 2).getStyle().setBackgroundColor("#F99");
            rf.getElement(i * 2 + 1).getStyle().setBackgroundColor("#F99");
          } else {
            rf.getElement(i * 2).getStyle().clearBackgroundColor();
            rf.getElement(i * 2 + 1).getStyle().clearBackgroundColor();
            if ((i % 2) == 0) {
              rf.addStyleName(i * 2, "subg");
              rf.addStyleName(i * 2 + 1, "subg");
            }
          }
          pos += part.length() + 1;
          logs.setText(i * 2, 1, part); // level
          part = msg.substring(pos, msg.indexOf('\t', pos));
          pos += part.length() + 1;
          logs.setText(i * 2, 2, part); // thread
          part = msg.substring(pos, msg.indexOf('\t', pos));
          pos += part.length() + 1;
          if (part.startsWith("net.opentsdb.")) {
            part = part.substring(13);
          } else if (part.startsWith("org.hbase.")) {
            part = part.substring(10);
          }
          logs.setText(i * 2, 3, part); // logger
          logs.setText(i * 2 + 1, 0, msg.substring(pos)); // message
          fcf.setColSpan(i * 2 + 1, 0, 4);
          rf.addStyleName(i * 2, "fwf");
          rf.addStyleName(i * 2 + 1, "fwf");
        }
      }
    });
  }

  private void refreshGraph() {
    final Date start = start_datebox.getValue();
    if (start == null) {
      graphstatus.setText("Please specify a start time.");
      return;
    }
    final Date end = end_datebox.getValue();
    if (end != null && !autoreoload.isChecked()) {
      if (end.getTime() <= start.getTime()) {
        end_datebox.addStyleName("dateBoxFormatError");
        graphstatus.setText("End time must be after start time!");
        return;
      }
    }
    final StringBuilder url = new StringBuilder();
    url.append("/q?start=").append(FULLDATE.format(start));
    if (end != null && !autoreoload.isChecked()) {
      url.append("&end=").append(FULLDATE.format(end));
    } else {
      // If there's no end-time, the graph may change while the URL remains
      // the same.  No browser seems to re-fetch an image once it's been
      // fetched, even if we destroy the DOM object and re-created it with the
      // same src attribute.  This has nothing to do with caching headers sent
      // by the server.  The browsers simply won't retrieve the same URL again
      // through JavaScript manipulations, period.  So as a workaround, we add
      // a special parameter that the server will delete from the query.
      url.append("&ignore=" + nrequests++);
    }
    if (!addAllMetrics(url)) {
      return;
    }
    {
      final String yrange = this.yrange.getText();
      if (!yrange.isEmpty()) {
        url.append("&yrange=").append(yrange);
      }
      if (y2range.isEnabled()) {
        final String y2range = this.y2range.getText();
        if (!y2range.isEmpty()) {
          url.append("&y2range=").append(y2range);
        }
      }
    }
    url.append("&wxh=").append(wxh.getText());
    final String uri = url.toString();
    if (uri.equals(lastgraphuri)) {
      return;  // Don't re-request the same graph.
    }
    lastgraphuri = uri;
    graphstatus.setText("Loading graph...");
    asyncGetJson(uri + "&json", new GotJsonCallback() {
      public void got(final JSONValue json) {
        if (autoreoload_timer != null) {
          autoreoload_timer.cancel();
          autoreoload_timer = null;
        }
        final JSONObject result = json.isObject();
        final JSONValue err = result.get("err");
        String msg = "";
        if (err != null) {
          displayError("An error occurred while generating the graph: "
                       + err.isString().stringValue());
          graphstatus.setText("Please correct the error above.");
        } else {
          clearError();
          final JSONValue nplotted = result.get("plotted");
          final JSONValue cachehit = result.get("cachehit");
          if (cachehit != null) {
            msg += "Cache hit (" + cachehit.isString().stringValue() + "). ";
          }
          if (nplotted != null && nplotted.isNumber().doubleValue() > 0) {
            graph.setUrl(uri + "&png");
            graph.setVisible(true);
            msg += result.get("points").isNumber() + " points retrieved, "
              + nplotted + " points plotted";
          } else {
            graph.setVisible(false);
            msg += "Your query didn't return anything";
          }
          final JSONValue timing = result.get("timing");
          if (timing != null) {
            msg += " in " + timing + "ms.";
          } else {
            msg += '.';
          }
        }
        final JSONValue info = result.get("info");
        if (info != null) {
          if (!msg.isEmpty()) {
            msg += ' ';
          }
          msg += info.isString().stringValue();
        }
        graphstatus.setText(msg);
        if (result.get("etags") != null) {
          final JSONArray etags = result.get("etags").isArray();
          final int netags = etags.size();
          for (int i = 0; i < netags; i++) {
            if (i >= metrics.getWidgetCount()) {
              break;
            }
            final Widget widget = metrics.getWidget(i);
            if (!(widget instanceof MetricForm)) {
              break;
            }
            final MetricForm metric = (MetricForm) widget;
            final JSONArray tags = etags.get(i).isArray();
            final int ntags = tags.size();
            for (int j = 0; j < ntags; j++) {
              metric.autoSuggestTag(tags.get(j).isString().stringValue());
            }
          }
        }
        if (autoreoload.isChecked()) {
          final int reload_in = Integer.parseInt(autoreoload_interval.getValue());
          if (reload_in >= 5) {
            autoreoload_timer = new Timer() {
              public void run() {
                // Verify that we still want auto reload and that the graph
                // hasn't been updated in the mean time.
                if (autoreoload.isChecked() && lastgraphuri == uri) {
                  // Force refreshGraph to believe that we want a new graph.
                  lastgraphuri = "";
                  refreshGraph();
                }
              }
            };
            autoreoload_timer.schedule(reload_in * 1000);
          }
        }
      }
    });
  }

  private boolean addAllMetrics(final StringBuilder url) {
    boolean found_metric = false;
    for (final Widget widget : metrics) {
      if (!(widget instanceof MetricForm)) {
        continue;
      }
      final MetricForm metric = (MetricForm) widget;
      found_metric |= metric.buildQueryString(url);
    }
    if (!found_metric) {
      graphstatus.setText("Please specify a metric.");
    }
    return found_metric;
  }

  private void asyncGetJson(final String url, final GotJsonCallback callback) {
    final RequestBuilder builder = new RequestBuilder(RequestBuilder.GET, url);
    try {
      builder.sendRequest(null, new RequestCallback() {
        public void onError(final Request request, final Throwable e) {
          displayError("Failed to get " + url + ": " + e.getMessage());
        }

        public void onResponseReceived(final Request request,
                                       final Response response) {
          final int code = response.getStatusCode();
          if (code == Response.SC_OK) {
            clearError();
            callback.got(JSONParser.parse(response.getText()));
            return;
          } else if (code >= Response.SC_BAD_REQUEST) {  // 400+ => Oops.
            displayError("Request failed while getting " + url + ": "
                         + response.getStatusText());
            graphstatus.setText("");
          }
        }
      });
    } catch (RequestException e) {
      displayError("Failed to get " + url + ": " + e.getMessage());
    }
  }

  private void displayError(final String errmsg) {
    current_error.setText(errmsg);
    current_error.setVisible(true);
  }

  private void clearError() {
    current_error.setVisible(false);
  }

}
