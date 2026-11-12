package br.com.varesync.report;

import br.com.varesync.model.ConteudoLiquido;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.service.EtiquetaService;
import br.com.varesync.service.NegocioException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.JobName;

/**
 * Etiquetas na impressora térmica (F18.1): o texto do {@link ModeloTermica} enviado em modo RAW (sem driver de
 * desenho: os bytes vão direto para a impressora) à impressora de {@code etiqueta.impressora_termica}. O envio é
 * separado ({@link Envio}) para os testes nunca mandarem nada para uma impressora de verdade. Roda em {@code Task}.
 */
public class ImpressaoTermica {

    /** Linguagens dos modelos de exemplo. */
    public enum Exemplo {
        ZPL("ZPL (Zebra)", "/etiquetas/exemplo-zpl.txt"),
        DPL("DPL (Datamax e Allegro)", "/etiquetas/exemplo-dpl.txt");

        private final String rotulo;
        private final String recurso;

        Exemplo(String rotulo, String recurso) {
            this.rotulo = rotulo;
            this.recurso = recurso;
        }

        public String rotulo() {
            return rotulo;
        }

        /** O texto do modelo de exemplo (precisa ser conferido na impressora da loja). */
        public String texto() {
            try (InputStream in = ImpressaoTermica.class.getResourceAsStream(recurso)) {
                if (in == null) {
                    throw new IllegalStateException("Modelo de exemplo não encontrado: " + recurso);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Manda os bytes à impressora com este nome. */
    @FunctionalInterface
    public interface Envio {
        void enviar(String impressora, byte[] dados);
    }

    private final Envio envio;

    public ImpressaoTermica() {
        this(ImpressaoTermica::enviarRaw);
    }

    public ImpressaoTermica(Envio envio) {
        this.envio = envio;
    }

    /**
     * @return quantas etiquetas foram enviadas
     * @throws NegocioException sem impressora ou modelo, ou se a impressora não existir ou recusar
     */
    public int imprimir(String impressora, String modelo, List<Etiqueta> etiquetas) {
        conferir(impressora, modelo);
        EtiquetaService.conferir(etiquetas);
        String texto = ModeloTermica.aplicar(modelo, etiquetas);
        envio.enviar(impressora.strip(), texto.getBytes(StandardCharsets.US_ASCII));
        return etiquetas.stream().mapToInt(Etiqueta::quantidade).sum();
    }

    /** "Imprimir teste": uma etiqueta de um produto fictício, com promoção e código de barras. */
    public void imprimirTeste(String impressora, String modelo, LocalDate hoje) {
        imprimir(impressora, modelo, List.of(exemplo(hoje)));
    }

    /** Produto fictício do teste. */
    public static Etiqueta exemplo(LocalDate hoje) {
        return new Etiqueta(0, "PRODUTO TESTE Açúcar Cristal 1 kg", "UN", new BigDecimal("12.90"),
                new BigDecimal("14.90"), -1L, hoje.plusDays(7), "7891000315507", null,
                ConteudoLiquido.SEM, hoje, null, false, 1, new BigDecimal("6"), new BigDecimal("11.90"));
    }

    private static void conferir(String impressora, String modelo) {
        if (impressora == null || impressora.isBlank()) {
            throw new NegocioException("Escolha a impressora térmica em Configurações › Etiquetas.");
        }
        if (modelo == null || modelo.isBlank()) {
            throw new NegocioException("Cadastre o modelo da etiqueta térmica em Configurações › Etiquetas (há "
                    + "exemplos em ZPL e DPL para começar).");
        }
    }

    /** Nomes das impressoras instaladas no Windows. */
    public static List<String> impressorasInstaladas() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null)).map(PrintService::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static Optional<PrintService> procurar(String nome) {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(p -> p.getName().equalsIgnoreCase(nome)).findFirst();
    }

    /** Envio RAW pelo spooler do Windows (bytes sem conversão). */
    static void enviarRaw(String nome, byte[] dados) {
        PrintService impressora = procurar(nome).orElseThrow(() -> new NegocioException("A impressora térmica \""
                + nome + "\" não foi encontrada no Windows. Confira o nome em Configurações › Etiquetas."));
        DocFlavor tipo = DocFlavor.BYTE_ARRAY.AUTOSENSE;
        if (!impressora.isDocFlavorSupported(tipo)) {
            throw new NegocioException("A impressora \"" + nome + "\" não aceita impressão direta (RAW).");
        }
        HashPrintRequestAttributeSet atributos = new HashPrintRequestAttributeSet();
        atributos.add(new JobName("VareSync - etiquetas", null));
        DocPrintJob trabalho = impressora.createPrintJob();
        try {
            trabalho.print(new SimpleDoc(dados, tipo, null), atributos);
        } catch (PrintException e) {
            throw new NegocioException("A impressora \"" + nome + "\" não aceitou as etiquetas. Confira se ela está "
                    + "ligada e com papel.", e);
        }
    }
}
