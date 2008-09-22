// CDDL HEADER START
//
// The contents of this file are subject to the terms of the
// Common Development and Distribution License, Version 1.0 only
// (the "License").  You may not use this file except in compliance
// with the License.
//
// You can obtain a copy of the license at
// trunk/opends/resource/legal-notices/OpenDS.LICENSE
// or https://OpenDS.dev.java.net/OpenDS.LICENSE.
// See the License for the specific language governing permissions
// and limitations under the License.
//
// When distributing Covered Code, include this CDDL HEADER in each
// file and include the License file at
// trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
// add the following below this CDDL HEADER, with the fields enclosed
// information:
//      Portions Copyright [yyyy] [name of copyright owner]
//
// CDDL HEADER END
//
//
//      Copyright 2008 Sun Microsystems, Inc.
import java.util.*;


public class Server {
  public String host;
  public int port;
  
  public Server (String host, int port) {
    this.host=host;
    this.port=port;
  }
  
  public Server (String hostPort) {
    StringTokenizer st = new StringTokenizer(hostPort, ":");
    this.host=st.nextToken();
    this.port=Integer.parseInt(st.nextToken());
  }
  
  public String toString() {
    return (host + ":" + port);
  }
}