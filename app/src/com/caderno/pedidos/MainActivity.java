package com.caderno.pedidos;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int PEDIR_ARQUIVO = 1;
    private static final int TIRAR_FOTO = 2;
    private WebView web;
    private ValueCallback<Uri[]> retornoArquivo;
    private Uri fotoCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ajustarBarras();
        boolean antigo = Build.VERSION.SDK_INT < 29;
        if (antigo) StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(antigo); // só o Android 8/9 precisa ler a foto da câmera por file://
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new Ponte(), "Android");
        web.setWebViewClient(new Navegador());
        web.setWebChromeClient(new SeletorArquivo());

        // Depois de uma atualização, descarta qualquer cópia antiga da página guardada pela WebView
        try {
            int versao = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            android.content.SharedPreferences pref = getSharedPreferences("app", MODE_PRIVATE);
            if (pref.getInt("versao", -1) != versao) {
                web.clearCache(true);
                pref.edit().putInt("versao", versao).apply();
            }
        } catch (Exception ignored) { }
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");
    }

    /**
     * A página é servida dos assets num endereço https próprio do app (o mesmo que o
     * WebViewAssetLoader do Android usa). Com origem https o Firebase consegue guardar
     * login e dados offline (IndexedDB), o que não funciona em file://.
     */
    private static final String HOST = "appassets.androidplatform.net";

    private class Navegador extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null; // Firebase e o resto vão pela internet
            String caminho = u.getPath() == null || u.getPath().equals("/") ? "index.html" : u.getPath().substring(1);
            try {
                return new WebResourceResponse(tipo(caminho), "UTF-8", getAssets().open(caminho));
            } catch (Exception e) {
                WebResourceResponse r = new WebResourceResponse("text/plain", "UTF-8", null);
                r.setStatusCodeAndReasonPhrase(404, "Not Found");
                return r;
            }
        }

        /** Links externos (WhatsApp etc.) abrem fora do app. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            if (HOST.equals(req.getUrl().getHost())) return false;
            abrirFora(req.getUrl().toString());
            return true;
        }
    }

    private static String tipo(String caminho) {
        if (caminho.endsWith(".html")) return "text/html";
        if (caminho.endsWith(".js")) return "application/javascript";
        if (caminho.endsWith(".css")) return "text/css";
        if (caminho.endsWith(".json")) return "application/json";
        if (caminho.endsWith(".png")) return "image/png";
        if (caminho.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** Abre a câmera, a galeria ou o seletor de arquivos, conforme o campo da página. */
    private class SeletorArquivo extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (retornoArquivo != null) retornoArquivo.onReceiveValue(null);
            retornoArquivo = cb;
            fotoCamera = null;
            boolean imagem = false;
            for (String t : p.getAcceptTypes()) if (t != null && t.startsWith("image")) imagem = true;
            if (imagem && p.isCaptureEnabled()) return abrirCamera();
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(imagem ? "image/*" : "*/*");
            try {
                startActivityForResult(Intent.createChooser(i, imagem ? "Escolha a foto" : "Escolha o backup"), PEDIR_ARQUIVO);
            } catch (Exception e) {
                retornoArquivo = null;
                return false;
            }
            return true;
        }
    }

    /** A câmera grava a foto em Imagens/Pedidos e a página lê de lá. */
    private boolean abrirCamera() {
        try {
            String nome = "produto-" + System.currentTimeMillis() + ".jpg";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, nome);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Pedidos");
                fotoCamera = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            } else {
                // Android 8 e 9: arquivo na pasta do app (sem FileProvider, liberado pelo StrictMode no onCreate)
                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                fotoCamera = Uri.fromFile(new File(dir, nome));
            }
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, fotoCamera);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i, TIRAR_FOTO);
            return true;
        } catch (Exception e) {
            descartarFotoCamera();
            retornoArquivo = null;
            return false;
        }
    }

    private void descartarFotoCamera() {
        if (fotoCamera != null && "content".equals(fotoCamera.getScheme())) {
            try { getContentResolver().delete(fotoCamera, null, null); } catch (Exception ignored) { }
        }
        fotoCamera = null;
    }

    private boolean noturno() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void ajustarBarras() {
        Window w = getWindow();
        boolean escuro = noturno();
        w.setStatusBarColor(Color.parseColor(escuro ? "#1a1310" : "#f7f0ea"));
        w.setNavigationBarColor(Color.parseColor(escuro ? "#241a15" : "#fffaf6"));
        int flags = 0;
        if (!escuro) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    private void abrirFora(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == TIRAR_FOTO && retornoArquivo != null) {
            if (res == RESULT_OK && fotoCamera != null) {
                retornoArquivo.onReceiveValue(new Uri[]{fotoCamera});
                fotoCamera = null;
            } else {
                descartarFotoCamera();
                retornoArquivo.onReceiveValue(null);
            }
            retornoArquivo = null;
            return;
        }
        if (req == PEDIR_ARQUIVO && retornoArquivo != null) {
            Uri[] r = (res == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
            retornoArquivo.onReceiveValue(r);
            retornoArquivo = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.voltar ? window.voltar() : false", r -> {
            if (!"true".equals(r)) finish();
        });
    }

    /** Métodos chamados pela página (window.Android). */
    private class Ponte {
        private File arquivo() { return new File(getFilesDir(), "dados.json"); }

        @JavascriptInterface
        public String carregar() {
            File f = arquivo();
            if (!f.exists()) f = new File(getFilesDir(), "dados.json.anterior");
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int lido = 0;
                while (lido < b.length) {
                    int n = in.read(b, lido, b.length - lido);
                    if (n < 0) break;
                    lido += n;
                }
                return new String(b, 0, lido, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        /** Grava num arquivo temporário e troca, guardando a versão anterior. */
        @JavascriptInterface
        public boolean salvar(String json) {
            File dir = getFilesDir();
            File tmp = new File(dir, "dados.json.tmp");
            File atual = arquivo();
            File anterior = new File(dir, "dados.json.anterior");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) {
                return false;
            }
            if (atual.exists()) {
                anterior.delete();
                atual.renameTo(anterior);
            }
            return tmp.renameTo(atual);
        }

        /** Salva o backup na pasta Downloads e devolve onde ficou ("" se falhar). */
        @JavascriptInterface
        public String salvarBackup(String nome, String json) {
            byte[] dados = json.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, nome);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(dados);
                    }
                    return "Downloads/" + nome;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, nome);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(dados);
                    }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void compartilhar(String texto) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, texto);
            runOnUiThread(() -> startActivity(Intent.createChooser(i, "Enviar cobrança")));
        }

        /** Versão instalada, lida do próprio Android (ex.: "2.3 (8)"). */
        @JavascriptInterface
        public String versao() {
            try {
                android.content.pm.PackageInfo i = getPackageManager().getPackageInfo(getPackageName(), 0);
                return i.versionName + " (" + i.versionCode + ")";
            } catch (Exception e) {
                return "?";
            }
        }

        @JavascriptInterface
        public void abrirLink(String url) {
            runOnUiThread(() -> abrirFora(url));
        }
    }
}
