"""The electronic diary, as a shape any provider can meet.

One diary is Petersburg's (`app.providers.petersburg`) and one is «Сетевой
город» (`app.providers.netschool`); more may follow. What they share lives
here so that the service, the routes and the bot depend on the shape rather
than on either upstream:

- :mod:`app.providers.diary.models` — the models every provider returns;
- :mod:`app.providers.diary.errors` — the failures every provider raises, each
  one a different answer at the API edge, down to the address the upstream
  will not talk to (:class:`AddressRefused`) and the sign-in a region does not
  offer (:class:`SignInUnsupported`);
- :mod:`app.providers.diary.base` — the :class:`DiaryProvider` and
  :class:`DiaryConnection` protocols, and the two ways a session reaches a
  provider: :class:`SignInRequest`, a password that passes through this server
  on its way upstream (the bot's sign-in page, and the
  ``POST /api/v1/diary/login`` older apps still call), and
  :class:`AdoptRequest`, a session the phone opened with the diary itself,
  which the provider checks with a read of its own and answers with
  :class:`Adopted`;
- :mod:`app.providers.diary.http` — the shared HTTP client, whose cookie jar
  keeps nothing so two families' sessions can never meet, which follows no
  redirect, and the one definition of a cookie or header value that is safe to
  write (:func:`cookie_value_ok`, :func:`header_value_ok`);
- :mod:`app.providers.diary.registry` — the map from a stored provider key to
  an implementation, and :func:`binding`, the one place that reads a class's
  diary binding.

Nothing is re-exported from here: each name is imported from the module above
that defines it, so importing this package costs no HTTP client.

Petersburg pre-dates this package. Its old modules,
`app.providers.petersburg.models` and `.exceptions`, re-export the very
objects defined here, so the imports that used them still work and
`isinstance` holds across the two spellings. The one name nothing read,
``DiaryAccount``, is gone (#138).
"""
