"""Integrations with services this project does not own.

Everything under here speaks somebody else's protocol, and the point of the
package boundary is that nothing above it has to. A provider returns this
project's own models; when the service behind it changes a field name, the
repair is inside one directory.
"""
