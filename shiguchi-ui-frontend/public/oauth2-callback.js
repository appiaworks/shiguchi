var params = new URLSearchParams(window.location.search);
if (window.opener) {
  window.opener.postMessage(
    {
      type: 'oauth2-callback',
      code: params.get('code'),
      error: params.get('error'),
      state: params.get('state'),
    },
    window.location.origin
  );
}
window.close();
