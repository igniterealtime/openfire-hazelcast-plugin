<%--
  -	$Revision: $
  -	$Date: $
  -
  - Copyright (C) 2006 Jive Software. All rights reserved.
  -
  - This software is published under the terms of the GNU Public License (GPL),
  - a copy of which is included in this distribution.
--%>

<%@ page import="org.jivesoftware.util.JiveGlobals" %>
<%@ page import="org.jivesoftware.util.ParamUtils" %>
<%@ page import="org.jivesoftware.wildfire.XMPPServer" %>
<%@ page import="org.jivesoftware.wildfire.mediaproxy.MediaProxyService" %>

<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="http://java.sun.com/jstl/fmt_rt" prefix="fmt" %>

<%

    MediaProxyService mediaProxyService = XMPPServer.getInstance().getMediaProxyService();

    boolean save = request.getParameter("set") != null;
    boolean success = false;

    long keepAliveDelay = 0;
    int minPort = 10000;
    int maxPort = 20000;
    boolean enabled = false;

    if (save) {
        keepAliveDelay = ParamUtils.getLongParameter(request, "keepalive", keepAliveDelay);
        if (keepAliveDelay > 50) {
            mediaProxyService.setKeepAliveDelay(keepAliveDelay);
            JiveGlobals
                    .setProperty("mediaproxy.keepalive", String.valueOf(keepAliveDelay));
        }

        minPort = ParamUtils.getIntParameter(request, "minport", minPort);
        maxPort = ParamUtils.getIntParameter(request, "maxport", maxPort);
        enabled = ParamUtils.getBooleanParameter(request, "enabled", enabled);

        JiveGlobals.setProperty("mediaproxy.enabled", String.valueOf(enabled));

        if (minPort > 0 && maxPort > 0) {
            if (maxPort - minPort > 1000) {
                mediaProxyService.setMinPort(minPort);
                mediaProxyService.setMaxPort(maxPort);
                JiveGlobals.setProperty("mediaproxy.portMin", String.valueOf(minPort));
                JiveGlobals.setProperty("mediaproxy.portMax", String.valueOf(maxPort));
            }
        }

        mediaProxyService.setEnabled(enabled);

        success = true;
    }

%>
<html>
<head>
    <title>Media Proxy</title>
    <meta name="pageID" content="media-proxy-service"/>
</head>
<body>

<p>
    The media proxy enables clients to make rich media (including VoIP) connections to one another
    when peer to peer connections fail, such as when one or both clients are behind a
    strict firewall.<br>
</p>

<% if (success) { %>

<div class="jive-success">
    <table cellpadding="0" cellspacing="0" border="0">
        <tbody>
            <tr>
                <td class="jive-icon"><img src="images/success-16x16.gif" width="16" height="16"
                                           border="0"></td>
                <td class="jive-icon-label">Settings updated successfully.</td>
            </tr>
        </tbody>
    </table>
</div>
<br>

<% } %>

<form action="media-proxy.jsp" method="post">
    <div class="jive-contentBoxHeader">
        Media Proxy Settings
    </div>
    <div class="jive-contentBox">
        <table cellpadding="3" cellspacing="0" border="0">
            <tbody>
                <tr valign="middle">
                    <td width="1%" nowrap>
                        <input type="radio" name="proxyEnabled" value="true" id="rb02"
                        <%= (enabled ? "checked" : "") %> >
                    </td>
                    <td width="99%">
                        <label for="rb02">
                            <b>Enabled</b>
                            - This server will act as a media proxy.
                        </label>
                        <br>

                        Session Idle Timeout:&nbsp<input type="text" size="5" maxlength="8" name="idleTimeout"
                                                         value="<%=mediaProxyService.getIdleTime()/1000%>"
                                                         align="left">

                        <input type="text" size="5" maxlength="10" name="port"
                               value="<%= 38 %>">
                    </td>
                </tr>
                <tr valign="middle">
                    <td width="1%" nowrap>
                        <input type="radio" name="proxyEnabled" value="false" id="rb01"
                        <%= (!enabled ? "checked" : "") %> >
                    </td>
                    <td width="99%">
                        <label for="rb01">
                            <b>Disabled</b>
                            - This server will not act as a media proxy.
                        </label>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
    <input type="submit" name="update" value="<fmt:message key="global.save_settings" />">
</form>


<form action="media-proxy.jsp" method="post">
    <fieldset>
        <legend>Media Proxy Settings</legend>
        <div>

            <p>
                The settings will just take effects for new created agents.
            </p>

            <table cellpadding="3" cellspacing="0" border="0" width="100%">
                <tbody>
                    <tr>
                        <td align="left">Idle Timeout:&nbsp<input type="text" size="20"
                                                                  maxlength="100"
                                                                  name="keepalivedelay"
                                                                  value="<%=mediaProxyService.getIdleTime()%>"
                                                                  align="left">
                        </td>
                    </tr>
                    <tr>
                        <td align="left">Port Range: Min&nbsp<input type="text" size="20"
                                                                    maxlength="100"
                                                                    name="minport"
                                                                    value="<%=mediaProxyService.getMinPort()%>"
                                                                    align="left">
                        </td>
                    </tr>
                    <tr>
                        <td align="left">Max:&nbsp<input type="text" size="20"
                                                         maxlength="100"
                                                         name="maxport"
                                                         value="<%=mediaProxyService.getMaxPort()%>"
                                                         align="left">
                        </td>
                    </tr>
                    <tr>
                        <td align="left">Enabled:&nbsp<input type="checkbox"
                                                             name="enabled"
                        <%=mediaProxyService.isEnabled()?"checked":""%>
                                                             align="left">
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
        <input type="submit" name="set" value="Change">

    </fieldset>
</form>

</body>
</html>