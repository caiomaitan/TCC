package br.com.varesync.report;

import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Definição de uma coluna de relatório (7.4): título, tipo (formatação e alinhamento), o valor de cada linha e, se
 * for totalizável, como calcular o total. O total recebe todas as linhas: soma para valores, e a fórmula do
 * agregado para margem, markup e ticket médio (Σ lucro ÷ Σ receita, nunca média de margens).
 *
 * <p>Valores por tipo: TEXTO {@code String}; INTEIRO {@code Number}; MOEDA, CUSTO, QUANTIDADE e PERCENTUAL
 * {@code BigDecimal}; DATA {@code LocalDate}; DATA_HORA {@code OffsetDateTime}. Nulo aparece como "—" (ex.:
 * margem com receita zero).
 *
 * @param fracionada para QUANTIDADE: a unidade da linha aceita casas decimais? Nulo = mostra 3 casas só se houver
 *                   fração.
 */
public record Coluna<T>(String titulo, TipoColuna tipo, TipoColuna.Alinhamento alinhamento, double largura,
                        Function<T, ?> valor, Function<List<T>, ?> total, Predicate<T> fracionada) {

    private static final DateTimeFormatter DATA_CSV = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA_CSV = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public Coluna {
        Objects.requireNonNull(titulo);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(valor);
        alinhamento = alinhamento == null ? tipo.alinhamento() : alinhamento;
        largura = largura <= 0 ? tipo.largura() : largura;
    }

    public static <T> Coluna<T> de(String titulo, TipoColuna tipo, Function<T, ?> valor) {
        return new Coluna<>(titulo, tipo, null, 0, valor, null, null);
    }

    public static <T> Coluna<T> texto(String titulo, Function<T, String> valor) {
        return de(titulo, TipoColuna.TEXTO, valor);
    }

    /** Valor em reais, somado na linha de totais. */
    public static <T> Coluna<T> moeda(String titulo, Function<T, BigDecimal> valor) {
        return Coluna.<T>de(titulo, TipoColuna.MOEDA, valor).somada();
    }

    public static <T> Coluna<T> inteiro(String titulo, Function<T, ? extends Number> valor) {
        return Coluna.<T>de(titulo, TipoColuna.INTEIRO, valor).somada();
    }

    public static <T> Coluna<T> percentual(String titulo, Function<T, BigDecimal> valor) {
        return de(titulo, TipoColuna.PERCENTUAL, valor);
    }

    public static <T> Coluna<T> quantidade(String titulo, Function<T, BigDecimal> valor, Predicate<T> fracionada) {
        return new Coluna<>(titulo, TipoColuna.QUANTIDADE, null, 0, valor, null, fracionada);
    }

    public Coluna<T> comTotal(Function<List<T>, ?> calculo) {
        return new Coluna<>(titulo, tipo, alinhamento, largura, valor, calculo, fracionada);
    }

    /** Total = soma dos valores da coluna (nulos ignorados). */
    public Coluna<T> somada() {
        if (tipo == TipoColuna.INTEIRO) {
            return comTotal(linhas -> linhas.stream().map(valor).filter(Objects::nonNull)
                    .mapToLong(v -> ((Number) v).longValue()).sum());
        }
        return comTotal(linhas -> soma(linhas, l -> (BigDecimal) valor.apply(l)));
    }

    public Coluna<T> semTotal() {
        return comTotal(null);
    }

    public Coluna<T> comLargura(double novaLargura) {
        return new Coluna<>(titulo, tipo, alinhamento, novaLargura, valor, total, fracionada);
    }

    public boolean totalizavel() {
        return total != null;
    }

    public Object valorDe(T linha) {
        return valor.apply(linha);
    }

    public Object totalDe(List<T> linhas) {
        return total == null ? null : total.apply(linhas);
    }

    /** Texto da célula, como na tela e no PDF. */
    public String formatar(T linha) {
        return formatar(valorDe(linha), fracionada == null ? null : fracionada.test(linha));
    }

    /** Texto do total; vazio se a coluna não é totalizável. */
    public String formatarTotal(List<T> linhas) {
        return totalizavel() ? formatar(totalDe(linhas), null) : "";
    }

    /** Valor para o CSV: sem "R$", sem separador de milhar e com vírgula decimal, para o Excel ler como número. */
    public String csv(T linha) {
        return csv(valorDe(linha), fracionada == null ? null : fracionada.test(linha));
    }

    public String csvTotal(List<T> linhas) {
        return totalizavel() ? csv(totalDe(linhas), null) : "";
    }

    /** Ordena pelo valor bruto (número, data ou texto), não pelo texto formatado. Nulos primeiro. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Comparator<Object> comparador() {
        return Comparator.nullsFirst((a, b) -> {
            if (a instanceof Number x && b instanceof Number y && !(a instanceof BigDecimal)) {
                return Long.compare(x.longValue(), y.longValue());
            }
            if (a instanceof String x && b instanceof String y) {
                return x.compareToIgnoreCase(y);
            }
            return ((Comparable) a).compareTo(b);
        });
    }

    String formatar(Object v, Boolean fracionadaLinha) {
        if (v == null) {
            return Formatos.SEM_VALOR;
        }
        return switch (tipo) {
            case TEXTO -> v.toString();
            case INTEIRO -> Formatos.numero(BigDecimal.valueOf(((Number) v).longValue()), 0);
            case MOEDA -> Formatos.moeda((BigDecimal) v);
            case CUSTO -> ((BigDecimal) v).signum() < 0 ? "-R$ " + Formatos.numero(((BigDecimal) v).abs(),
                    Dinheiro.ESCALA_CUSTO) : "R$ " + Formatos.numero((BigDecimal) v, Dinheiro.ESCALA_CUSTO);
            case QUANTIDADE -> Formatos.quantidade((BigDecimal) v, fracionada((BigDecimal) v, fracionadaLinha));
            case PERCENTUAL -> Formatos.percentual((BigDecimal) v);
            case DATA -> Formatos.data((LocalDate) v);
            case DATA_HORA -> Formatos.dataHora((OffsetDateTime) v);
        };
    }

    String csv(Object v, Boolean fracionadaLinha) {
        if (v == null) {
            return "";
        }
        return switch (tipo) {
            case TEXTO -> v.toString();
            case INTEIRO -> Long.toString(((Number) v).longValue());
            case MOEDA -> decimal((BigDecimal) v, Dinheiro.ESCALA_DINHEIRO);
            case CUSTO -> decimal((BigDecimal) v, Dinheiro.ESCALA_CUSTO);
            case QUANTIDADE -> decimal((BigDecimal) v, fracionada((BigDecimal) v, fracionadaLinha)
                    ? Dinheiro.ESCALA_QUANTIDADE : 0);
            case PERCENTUAL -> decimal((BigDecimal) v, Dinheiro.ESCALA_PERCENTUAL);
            case DATA -> DATA_CSV.format((LocalDate) v);
            case DATA_HORA -> DATA_HORA_CSV.format(((OffsetDateTime) v).atZoneSameInstant(ZoneId.systemDefault()));
        };
    }

    private static boolean fracionada(BigDecimal v, Boolean fracionadaLinha) {
        if (fracionadaLinha != null) {
            return fracionadaLinha;
        }
        return v.stripTrailingZeros().scale() > 0;
    }

    private static String decimal(BigDecimal v, int casas) {
        return v.setScale(casas, Dinheiro.ARREDONDAMENTO).toPlainString().replace('.', ',');
    }

    /** Soma de uma coluna BigDecimal, para as fórmulas de agregado. */
    public static <T> BigDecimal soma(List<T> linhas, Function<T, BigDecimal> valor) {
        BigDecimal soma = BigDecimal.ZERO;
        for (T linha : linhas) {
            BigDecimal v = valor.apply(linha);
            if (v != null) {
                soma = soma.add(v);
            }
        }
        return soma;
    }
}
