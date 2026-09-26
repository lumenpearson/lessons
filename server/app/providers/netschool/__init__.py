"""«Сетевой город. Образование» (ИРТех NetSchool), behind one door.

The one route set of `/webapi`, on a different regional server in each region.
Nothing outside this package knows a host, a cookie name or an `at` token; what
leaves here are the shared diary models and the shared diary errors.

`regions` is the allow-list — the only origins this server ever contacts for
this provider, so a region key from a client, a class row or a stored
credential can never become an arbitrary URL. It imports nothing, so reading a
class's binding costs no HTTP module.
"""

from app.providers.netschool.regions import Region, get, listed

__all__ = ["Region", "get", "listed"]
