## taken from SET09123 2025-6 TR2 001 - Interactive Data Visualisation

import os
from http.server import SimpleHTTPRequestHandler, HTTPServer

os.chdir('.')
server_address = ('', 8000)
Handler = SimpleHTTPRequestHandler
Handler.extensions_map.update({
      ".js": "application/javascript",
})
httpd = HTTPServer(server_address, Handler)
httpd.serve_forever()