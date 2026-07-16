
package com.gabriel.ejapertodemim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String SCHOOLS_URL =
            "https://goias.gov.br/educacao/lista-de-escolas-rede-estadual-de-educacao/";
    private static final String ENROLL_URL = "https://matricula.go.gov.br/";
    private static final String VACANCIES_URL =
            "https://goias.gov.br/educacao/consulta-de-vagas-disponiveis-para-matricula-escolar/";
    private static final String EJA_INFO_URL =
            "https://goias.gov.br/educacao/educacao-de-jovens-e-adultos/";
    private static final String CEP_API = "https://brasilapi.com.br/api/cep/v2/";

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Pattern codePattern = Pattern.compile("\\b(52\\d{6})\\b");
    private final Pattern cepPattern = Pattern.compile("(?i)Cep:\\s*([0-9.\\-]{8,10})");
    private final Pattern emailPattern = Pattern.compile("[A-Za-z0-9._%+-]+@seduc\\.go\\.gov\\.br", Pattern.CASE_INSENSITIVE);
    private final Pattern addressPattern = Pattern.compile(
            "(?i)\\b(RUA|R\\.|R |AVENIDA|AV\\.|AV |PRAÇA|PRACA|PÇ|QUADRA|QD|RODOVIA|ROD\\.|ESTRADA|ALAMEDA|TRAVESSA|FAZENDA)\\b"
    );

    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout resultsBox;
    private EditText cepInput;
    private Spinner stageSpinner;
    private TextView status;
    private TextView addressText;
    private ProgressBar progress;
    private Button searchButton;
    private Button refreshButton;
    private String officialHtml = "";
    private UserLocation userLocation;

    private final ArrayList<School> currentResults = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("eja_perto_go", MODE_PRIVATE);
        buildUi();

        String savedCep = prefs.getString("last_cep", "");
        cepInput.setText(savedCep);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(34));
        root.setBackgroundColor(Color.parseColor("#F4F8F6"));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scroll);

        TextView title = text("EJA Perto de Mim GO", 24, true, "#0B3B2E");
        root.addView(title);

        TextView subtitle = text(
                "Digite seu CEP para localizar escolas da rede estadual de Goiás que aparecem na lista oficial com oferta de EJA.",
                14, false, "#3E5B52"
        );
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle);

        LinearLayout cepRow = row();
        cepInput = new EditText(this);
        cepInput.setHint("CEP: 00000-000");
        cepInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        cepInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        cepInput.setSingleLine(true);
        cepInput.setPadding(dp(12), 0, dp(12), 0);
        cepInput.setBackgroundColor(Color.WHITE);
        cepRow.addView(cepInput, new LinearLayout.LayoutParams(0, dp(54), 1));

        searchButton = button("Pesquisar");
        searchButton.setOnClickListener(v -> search());
        cepRow.addView(searchButton, new LinearLayout.LayoutParams(dp(125), dp(54)));
        root.addView(cepRow);

        LinearLayout filterRow = row();
        TextView stageLabel = text("Etapa:", 14, true, "#0B3B2E");
        filterRow.addView(stageLabel);

        stageSpinner = new Spinner(this);
        String[] stages = {"Todas", "Fundamental", "Ensino Médio", "EJA profissional"};
        ArrayAdapter<String> stageAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, stages
        );
        stageSpinner.setAdapter(stageAdapter);
        filterRow.addView(stageSpinner, new LinearLayout.LayoutParams(0, dp(50), 1));

        refreshButton = button("Atualizar base");
        refreshButton.setOnClickListener(v -> {
            officialHtml = "";
            prefs.edit().remove("official_html").remove("official_updated").apply();
            File cacheFile = new File(getCacheDir(), "seduc_escolas.html");
            if (cacheFile.exists()) cacheFile.delete();
            Toast.makeText(
                    this,
                    "Base local apagada. A próxima busca baixará a lista oficial novamente.",
                    Toast.LENGTH_LONG
            ).show();
        });
        filterRow.addView(refreshButton, new LinearLayout.LayoutParams(dp(145), dp(50)));
        root.addView(filterRow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
        ));

        status = text("Pronto para pesquisar.", 13, false, "#3E5B52");
        status.setPadding(0, dp(8), 0, dp(3));
        root.addView(status);

        addressText = text("", 13, true, "#0B3B2E");
        addressText.setPadding(0, 0, 0, dp(10));
        root.addView(addressText);

        LinearLayout officialButtonsTop = row();
        Button enroll = button("Inscrição oficial");
        enroll.setOnClickListener(v -> openUrl(ENROLL_URL));
        Button vacancies = button("Consultar vagas");
        vacancies.setOnClickListener(v -> openUrl(VACANCIES_URL));
        officialButtonsTop.addView(enroll, tallWeightedButton());
        officialButtonsTop.addView(vacancies, tallWeightedButton());
        root.addView(officialButtonsTop);

        LinearLayout officialButtonsBottom = row();
        Button info = button("Regras da EJA");
        info.setOnClickListener(v -> openUrl(EJA_INFO_URL));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        );
        infoParams.setMargins(dp(2), dp(2), dp(2), dp(4));
        officialButtonsBottom.addView(info, infoParams);
        root.addView(officialButtonsBottom);

        TextView warning = text(
                "Importante: o aplicativo confirma a oferta de EJA na lista oficial, mas a vaga atual precisa ser confirmada no portal de matrícula ou diretamente com a escola.",
                12, false, "#7A4C00"
        );
        warning.setBackgroundColor(Color.parseColor("#FFF4D6"));
        warning.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(warning);

        TextView resultTitle = text("Escolas encontradas", 19, true, "#0B3B2E");
        resultTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(resultTitle);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);

        TextView source = text(
                "Fonte das escolas e modalidades: Secretaria de Estado da Educação de Goiás. Localização por CEP: BrasilAPI. Matrícula: portal oficial do Governo de Goiás.",
                11, false, "#60756E"
        );
        source.setPadding(0, dp(18), 0, 0);
        root.addView(source);
    }

    private void search() {
        String cep = digits(cepInput.getText().toString());
        if (cep.length() != 8) {
            showError("Digite um CEP com 8 números.");
            return;
        }

        prefs.edit().putString("last_cep", cep).apply();
        final String selectedStage = stageSpinner.getSelectedItem().toString();
        searchButton.setEnabled(false);
        refreshButton.setEnabled(false);
        resultsBox.removeAllViews();
        currentResults.clear();
        progress.setIndeterminate(true);
        status.setText("Consultando seu CEP...");
        addressText.setText("");

        executor.execute(() -> {
            try {
                userLocation = fetchCep(cep);
                if (!"GO".equalsIgnoreCase(userLocation.state)) {
                    runOnUiThread(() -> {
                        finishLoading();
                        new AlertDialog.Builder(this)
                                .setTitle("Versão de Goiás")
                                .setMessage("Esta primeira versão consulta a lista oficial da rede estadual de Goiás. O CEP informado pertence a " + userLocation.state + ".")
                                .setPositiveButton("OK", null)
                                .show();
                    });
                    return;
                }

                runOnUiThread(() -> {
                    addressText.setText(userLocation.fullAddress());
                    status.setText("Baixando a lista oficial de escolas...");
                });

                officialHtml = getOfficialHtml();
                ArrayList<School> detectedSchools = parseSchools(officialHtml, userLocation);
                if (detectedSchools.isEmpty()) {
                    throw new IOException(
                            "A página oficial foi acessada, mas o formato da lista não pôde ser lido. " +
                            "Toque em Atualizar base e tente novamente."
                    );
                }

                final int totalDetected = detectedSchools.size();
                ArrayList<School> schools = filterStage(detectedSchools, selectedStage);

                if (schools.isEmpty()) {
                    runOnUiThread(() -> {
                        finishLoading();
                        status.setText(
                                "A base oficial foi lida (" + totalDetected +
                                " escola(s) com EJA), mas nenhuma corresponde ao filtro selecionado."
                        );
                    });
                    return;
                }

                runOnUiThread(() -> status.setText("Calculando as escolas mais próximas..."));

                ArrayList<School> candidates = chooseCandidates(schools, userLocation);
                geocodeCandidates(candidates, userLocation);

                candidates.sort((a, b) -> {
                    if (a.hasDistance && b.hasDistance) return Double.compare(a.distanceKm, b.distanceKm);
                    if (a.hasDistance) return -1;
                    if (b.hasDistance) return 1;
                    return Long.compare(a.cepDifference, b.cepDifference);
                });

                if (candidates.size() > 20) {
                    candidates = new ArrayList<>(candidates.subList(0, 20));
                }

                ArrayList<School> finalCandidates = candidates;
                runOnUiThread(() -> {
                    finishLoading();
                    currentResults.clear();
                    currentResults.addAll(finalCandidates);
                    status.setText(finalCandidates.size() + " escola(s) exibida(s), em ordem de proximidade.");
                    renderResults(finalCandidates);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    finishLoading();
                    showError(e.getMessage());
                });
            }
        });
    }

    private void finishLoading() {
        progress.setIndeterminate(false);
        progress.setProgress(0);
        searchButton.setEnabled(true);
        refreshButton.setEnabled(true);
    }

    private UserLocation fetchCep(String cep) throws Exception {
        String json = httpGet(CEP_API + cep, "EJA-Perto-de-Mim-GO/1.0");
        JSONObject root = new JSONObject(json);

        UserLocation u = new UserLocation();
        u.cep = digits(root.optString("cep", cep));
        u.state = root.optString("state", "");
        u.city = root.optString("city", "");
        u.neighborhood = root.optString("neighborhood", "");
        u.street = root.optString("street", "");

        JSONObject location = root.optJSONObject("location");
        if (location != null) {
            JSONObject coordinates = location.optJSONObject("coordinates");
            if (coordinates != null) {
                u.longitude = parseDouble(coordinates.opt("longitude"));
                u.latitude = parseDouble(coordinates.opt("latitude"));
                u.hasCoordinates = !Double.isNaN(u.latitude) && !Double.isNaN(u.longitude);
            }
        }
        return u;
    }

    private String getOfficialHtml() throws Exception {
        if (!officialHtml.isEmpty()) return officialHtml;

        File cacheFile = new File(getCacheDir(), "seduc_escolas.html");
        long maxAge = 7L * 24 * 60 * 60 * 1000;
        if (cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < maxAge) {
            try (FileInputStream input = new FileInputStream(cacheFile)) {
                officialHtml = readAll(input);
                if (!officialHtml.isEmpty()) return officialHtml;
            }
        }

        String html = httpGet(
                SCHOOLS_URL,
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
        );
        officialHtml = html;

        try (FileOutputStream output = new FileOutputStream(cacheFile)) {
            output.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        return html;
    }

    private ArrayList<School> parseSchools(String html, UserLocation user) {
        ArrayList<School> tableSchools = parseTableRows(html, user);
        if (!tableSchools.isEmpty()) return tableSchools;
        return parsePlainTextSchools(html, user);
    }

    private ArrayList<School> parseTableRows(String html, UserLocation user) {
        ArrayList<School> schools = new ArrayList<>();
        HashSet<String> dedupe = new HashSet<>();

        Pattern rowPattern = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
        Pattern cellPattern = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
        Matcher rowMatcher = rowPattern.matcher(html);

        while (rowMatcher.find()) {
            ArrayList<String> cells = new ArrayList<>();
            Matcher cellMatcher = cellPattern.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(decodeHtmlCell(cellMatcher.group(1)));
            }
            if (cells.size() < 7) continue;

            int codeIndex = -1;
            String codeValue = "";
            for (int i = 0; i < cells.size(); i++) {
                String candidate = digits(cells.get(i));
                if (candidate.matches("52\\d{6}")) {
                    codeIndex = i;
                    codeValue = candidate;
                    break;
                }
            }

            if (codeIndex < 2 || codeIndex + 4 >= cells.size()) continue;

            String municipality = cells.get(codeIndex - 1);
            String coordination = cells.get(codeIndex - 2);
            String name = cells.get(codeIndex + 1);
            String modalities = cells.get(codeIndex + 2);
            String rawAddress = cells.get(codeIndex + 3);
            String emailCell = cells.get(codeIndex + 4);

            String normalizedModalities = normalize(modalities);
            if (!(normalizedModalities.contains("educacao de jovens e adultos") ||
                    normalizedModalities.contains("eja integrada"))) {
                continue;
            }

            Matcher cepMatcher = cepPattern.matcher(rawAddress);
            if (!cepMatcher.find()) continue;

            School school = new School();
            school.code = codeValue;
            school.cep = digits(cepMatcher.group(1));
            if (school.cep.length() != 8) continue;

            school.name = clean(name);
            school.modalities = clean(modalities);
            school.address = clean(rawAddress.substring(0, cepMatcher.start()));
            school.prefix = clean(coordination + " " + municipality);

            Matcher emailMatcher = emailPattern.matcher(emailCell);
            school.email = emailMatcher.find()
                    ? emailMatcher.group()
                    : school.code + "@seduc.go.gov.br";

            school.sameCity = normalize(municipality).equals(normalize(user.city));
            school.cepDifference = Math.abs(parseLong(school.cep) - parseLong(user.cep));

            if (school.name.length() < 4) continue;
            String key = school.code + "|" + school.cep;
            if (dedupe.add(key)) schools.add(school);
        }

        return schools;
    }

    private ArrayList<School> parsePlainTextSchools(String html, UserLocation user) {
        String plain = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        String[] chunks = plain.split(
                "(?=(?:CRE-|CENTRO DE ATEND\\\\. ED\\\\. FLORESCER))"
        );
        ArrayList<School> schools = new ArrayList<>();
        HashSet<String> dedupe = new HashSet<>();

        for (String chunk : chunks) {
            String normalizedChunk = normalize(chunk);
            if (!(normalizedChunk.contains("educacao de jovens e adultos") ||
                    normalizedChunk.contains("eja integrada"))) {
                continue;
            }

            Matcher code = codePattern.matcher(chunk);
            Matcher cep = cepPattern.matcher(chunk);
            if (!code.find() || !cep.find()) continue;

            int modalityStart = firstPositive(
                    chunk.indexOf("Educação de Jovens e Adultos", code.end()),
                    chunk.indexOf("EJA Integrada", code.end()),
                    chunk.indexOf("Educação Especial de Jovens e Adultos", code.end())
            );
            if (modalityStart < 0 || modalityStart >= cep.start()) continue;

            Matcher address = addressPattern.matcher(chunk);
            int addressStart = -1;
            while (address.find()) {
                if (address.start() > modalityStart && address.start() < cep.start()) {
                    addressStart = address.start();
                    break;
                }
            }
            if (addressStart < 0) addressStart = cep.start();

            School school = new School();
            school.code = code.group(1);
            school.cep = digits(cep.group(1));
            if (school.cep.length() != 8) continue;

            school.name = clean(chunk.substring(code.end(), modalityStart));
            school.modalities = clean(chunk.substring(modalityStart, addressStart));
            school.address = clean(chunk.substring(addressStart, cep.start()));
            school.prefix = clean(chunk.substring(0, code.start()));

            Matcher email = emailPattern.matcher(chunk.substring(cep.end()));
            school.email = email.find() ? email.group() : school.code + "@seduc.go.gov.br";
            school.sameCity = normalize(school.prefix).contains(normalize(user.city));
            school.cepDifference = Math.abs(parseLong(school.cep) - parseLong(user.cep));

            if (school.name.length() < 4) continue;
            String key = school.code + "|" + school.cep;
            if (dedupe.add(key)) schools.add(school);
        }
        return schools;
    }

    private String decodeHtmlCell(String value) {
        return clean(Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString());
    }

    private ArrayList<School> filterStage(ArrayList<School> schools, String stage) {
        if ("Todas".equals(stage)) return schools;
        ArrayList<School> filtered = new ArrayList<>();
        for (School s : schools) {
            String m = normalize(s.modalities);
            boolean ok;
            if ("Fundamental".equals(stage)) {
                ok = m.contains("primeira etapa") || m.contains("segunda etapa") || m.contains("fundamental");
            } else if ("Ensino Médio".equals(stage)) {
                ok = m.contains("terceira etapa") || m.contains("medio");
            } else {
                ok = m.contains("eja integrada") || m.contains("qualificacao profissional");
            }
            if (ok) filtered.add(s);
        }
        return filtered;
    }

    private ArrayList<School> chooseCandidates(ArrayList<School> all, UserLocation user) {
        all.sort(Comparator.comparingLong(a -> a.cepDifference));

        LinkedHashMap<String, School> chosen = new LinkedHashMap<>();
        for (School s : all) {
            if (s.sameCity && chosen.size() < 20) {
                chosen.put(s.code, s);
            }
        }
        for (School s : all) {
            if (chosen.size() >= 25) break;
            chosen.put(s.code, s);
        }
        return new ArrayList<>(chosen.values());
    }

    private void geocodeCandidates(ArrayList<School> schools, UserLocation user) {
        if (!user.hasCoordinates) return;

        int completed = 0;
        for (School school : schools) {
            try {
                double[] cached = readCachedCoordinates(school.cep);
                if (cached != null) {
                    school.latitude = cached[0];
                    school.longitude = cached[1];
                    school.hasCoordinates = true;
                } else {
                    UserLocation location = fetchCep(school.cep);
                    if (location.hasCoordinates) {
                        school.latitude = location.latitude;
                        school.longitude = location.longitude;
                        school.hasCoordinates = true;
                        cacheCoordinates(school.cep, school.latitude, school.longitude);
                    }
                }

                if (school.hasCoordinates) {
                    school.distanceKm = haversine(
                            user.latitude, user.longitude,
                            school.latitude, school.longitude
                    );
                    school.hasDistance = true;
                }
            } catch (Exception ignored) {}

            completed++;
            int percent = Math.round(completed * 100f / Math.max(1, schools.size()));
            int finalPercent = percent;
            runOnUiThread(() -> {
                progress.setIndeterminate(false);
                progress.setProgress(finalPercent);
            });
        }
    }

    private void renderResults(ArrayList<School> schools) {
        resultsBox.removeAllViews();

        if (schools.isEmpty()) {
            resultsBox.addView(text("Nenhuma escola encontrada.", 14, false, "#3E5B52"));
            return;
        }

        int position = 1;
        for (School school : schools) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(13), dp(12), dp(13), dp(12));
            card.setBackgroundColor(Color.WHITE);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, dp(12));

            String distance = school.hasDistance
                    ? String.format(Locale.getDefault(), "%.1f km", school.distanceKm)
                    : "distância a confirmar no mapa";

            TextView rank = text(position + "º • " + distance, 13, true, "#0F7659");
            card.addView(rank);

            TextView name = text(school.name, 17, true, "#0B3B2E");
            name.setPadding(0, dp(4), 0, dp(5));
            card.addView(name);

            card.addView(text(school.modalities, 13, false, "#334E46"));

            String address = school.address + "\nCEP " + formatCep(school.cep) +
                    "\nCódigo INEP: " + school.code +
                    "\nE-mail: " + school.email;
            TextView details = text(address, 12, false, "#60756E");
            details.setPadding(0, dp(7), 0, dp(8));
            card.addView(details);

            LinearLayout actions1 = row();
            Button maps = button("Mapa");
            maps.setOnClickListener(v -> openMap(school));
            Button email = button("E-mail");
            email.setOnClickListener(v -> emailSchool(school));
            Button share = button("Compartilhar");
            share.setOnClickListener(v -> shareSchool(school));
            actions1.addView(maps, weightedButton());
            actions1.addView(email, weightedButton());
            actions1.addView(share, weightedButton());
            card.addView(actions1);

            LinearLayout actions2 = row();
            Button enroll = button("Inscrever");
            enroll.setOnClickListener(v -> openUrl(ENROLL_URL));
            Button confirm = button("Confirmar vaga");
            confirm.setOnClickListener(v -> openUrl(VACANCIES_URL));
            actions2.addView(enroll, new LinearLayout.LayoutParams(0, dp(48), 1));
            actions2.addView(confirm, new LinearLayout.LayoutParams(0, dp(48), 1));
            card.addView(actions2);

            resultsBox.addView(card, cardParams);
            position++;
        }
    }

    private void openMap(School school) {
        String query = school.name + ", " + school.address + ", CEP " + school.cep + ", Goiás";
        Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            startActivity(intent);
        } catch (Exception e) {
            openUrl("https://www.google.com/maps/search/?api=1&query=" +
                    urlEncode(query));
        }
    }

    private void emailSchool(School school) {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + school.email));
        intent.putExtra(Intent.EXTRA_SUBJECT, "Informações sobre matrícula na EJA");
        intent.putExtra(Intent.EXTRA_TEXT,
                "Olá! Gostaria de saber se há vaga disponível na EJA e quais documentos são necessários para matrícula.");
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Nenhum aplicativo de e-mail disponível.", Toast.LENGTH_LONG).show();
        }
    }

    private void shareSchool(School school) {
        String distance = school.hasDistance
                ? String.format(Locale.getDefault(), "%.1f km", school.distanceKm)
                : "distância a confirmar";
        String message = school.name + "\n" +
                school.modalities + "\n" +
                school.address + " - CEP " + formatCep(school.cep) + "\n" +
                school.email + "\n" +
                "Distância: " + distance + "\n" +
                "Matrícula oficial: " + ENROLL_URL;

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, message);
        startActivity(Intent.createChooser(intent, "Compartilhar escola"));
    }

    private String httpGet(String address, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7");
        connection.setRequestProperty("Cache-Control", "no-cache");

        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        String body = readAll(input);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IOException("O serviço respondeu com erro " + code + ".");
        }
        return body;
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void cacheCoordinates(String cep, double lat, double lon) {
        prefs.edit().putString("geo_" + cep, lat + "," + lon).apply();
    }

    private double[] readCachedCoordinates(String cep) {
        String value = prefs.getString("geo_" + cep, "");
        if (value.isEmpty()) return null;
        try {
            String[] parts = value.split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return null;
        }
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double radius = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) *
                        Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showError("Não foi possível abrir o endereço.");
        }
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("EJA Perto de Mim GO")
                .setMessage(message == null || message.trim().isEmpty()
                        ? "Ocorreu um erro. Confira sua internet e tente novamente."
                        : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private TextView text(String value, int sp, boolean bold, String color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private LinearLayout.LayoutParams tallWeightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int firstPositive(int... values) {
        int result = -1;
        for (int value : values) {
            if (value >= 0 && (result < 0 || value < result)) result = value;
        }
        return result;
    }

    private String clean(String value) {
        return value.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalize(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private long parseLong(String value) {
        try { return Long.parseLong(digits(value)); }
        catch (Exception e) { return Long.MAX_VALUE / 2; }
    }

    private double parseDouble(Object value) {
        try {
            if (value == null) return Double.NaN;
            String s = String.valueOf(value).replace(",", ".").trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return Double.NaN;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private String formatCep(String cep) {
        String d = digits(cep);
        return d.length() == 8 ? d.substring(0, 5) + "-" + d.substring(5) : d;
    }

    private String urlEncode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception e) { return value; }
    }

    static class UserLocation {
        String cep = "", state = "", city = "", neighborhood = "", street = "";
        double latitude = Double.NaN, longitude = Double.NaN;
        boolean hasCoordinates = false;

        String fullAddress() {
            StringBuilder b = new StringBuilder();
            if (!street.isEmpty()) b.append(street);
            if (!neighborhood.isEmpty()) {
                if (b.length() > 0) b.append(" • ");
                b.append(neighborhood);
            }
            if (!city.isEmpty()) {
                if (b.length() > 0) b.append("\n");
                b.append(city).append("/").append(state);
            }
            if (!cep.isEmpty()) b.append(" • CEP ").append(cep.substring(0,5)).append("-").append(cep.substring(5));
            return b.toString();
        }
    }

    static class School {
        String code = "", cep = "", name = "", modalities = "", address = "";
        String email = "", prefix = "";
        boolean sameCity = false, hasCoordinates = false, hasDistance = false;
        long cepDifference = Long.MAX_VALUE;
        double latitude, longitude, distanceKm;
    }
}
